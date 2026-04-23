package com.demo.grocery.service;

import com.demo.grocery.demo.DemoFaultConfig;
import com.demo.grocery.demo.SagaTrace;
import com.demo.grocery.domain.Order;
import com.demo.grocery.dto.Cart;
import com.demo.grocery.dto.CheckoutRequest;
import com.demo.grocery.exception.CheckoutException;
import com.demo.grocery.exception.PaymentFailedException;
import com.demo.grocery.exception.ServiceUnavailableException;
import com.demo.grocery.external.inventory.InventoryReservation;
import com.demo.grocery.external.inventory.InventoryService;
import com.demo.grocery.external.payment.PaymentRequest;
import com.demo.grocery.external.payment.PaymentResult;
import com.demo.grocery.external.payment.PaymentService;
import com.demo.grocery.external.promotion.PromotionResult;
import com.demo.grocery.external.promotion.PromotionService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.*;

/**
 * Orchestrates the checkout saga — a sequence of calls across three external services
 * where each step may need to be compensated (rolled back) if a later step fails.
 *
 * Happy path:
 *   checkAvailability → applyPromotion → reserveStock → processPayment → createOrder → commitReservation
 *
 * Failure paths and their compensations:
 *   • Inventory DOWN at checkAvailability  → immediate fail, no side effects, safe to retry
 *   • Inventory DOWN at reserveStock       → immediate fail, no side effects, safe to retry
 *   • Promotion DOWN (hard-fail mode)      → immediate fail, no side effects, safe to retry
 *   • Promotion DOWN (graceful-degrade)    → continues with zero discount — non-critical path
 *   • Payment DOWN / declined / timeout    → releaseReservation  (stock returned to pool)
 *   • Order persist fails                  → refund + releaseReservation
 *   • Inventory commit fails               → refund + cancelOrder + releaseReservation
 *
 * This class is intentionally NOT @Transactional because the saga spans external
 * services that cannot be wrapped in a single DB transaction.
 *
 * Payment timeout: the payment call runs on a dedicated thread so that a slow
 * payment service does not block indefinitely. If it exceeds paymentTimeoutMs,
 * the reservation is released and the caller sees ServiceUnavailableException.
 */
@Service
public class CheckoutService {

    private final InventoryService inventoryService;
    private final PaymentService paymentService;
    private final PromotionService promotionService;
    private final OrderService orderService;
    private final DemoFaultConfig demoFaultConfig;

    // Dedicated thread pool for payment calls; daemon threads so they don't block JVM shutdown.
    private final ExecutorService paymentExecutor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "payment-call");
        t.setDaemon(true);
        return t;
    });

    public CheckoutService(InventoryService inventoryService,
                           PaymentService paymentService,
                           PromotionService promotionService,
                           OrderService orderService,
                           DemoFaultConfig demoFaultConfig) {
        this.inventoryService = inventoryService;
        this.paymentService   = paymentService;
        this.promotionService = promotionService;
        this.orderService     = orderService;
        this.demoFaultConfig  = demoFaultConfig;
    }

    public Order checkout(Cart cart, CheckoutRequest request, SagaTrace trace) {
        Map<Long, Integer> quantities = cart.getQuantityMap();
        int timeoutMs = demoFaultConfig.getPaymentTimeoutMs();

        // ── Step 1 ─ Pre-flight availability check (read-only, no side effects) ──────
        // If inventory is DOWN here: fail immediately, nothing to compensate.
        try {
            inventoryService.checkAvailability(quantities);
            trace.ok("1", "checkAvailability");
        } catch (Exception e) {
            trace.failed("1", "checkAvailability", e.getMessage());
            throw e;
        }

        // ── Step 2 ─ Apply promo code (read-only, non-critical dependency) ───────────
        // ServiceUnavailableException respects the graceful-degradation toggle:
        //   true  → skip the discount and continue
        //   false → propagate and abort checkout
        PromotionResult promotion = PromotionResult.none();
        if (StringUtils.hasText(request.getPromoCode())) {
            try {
                promotion = promotionService.applyPromotion(request.getPromoCode(), cart.getSubtotal());
                trace.ok("2", "applyPromotion (" + promotion.code() + ")");
            } catch (ServiceUnavailableException e) {
                if (!demoFaultConfig.isPromotionGracefulDegradation()) {
                    trace.failed("2", "applyPromotion", e.getMessage());
                    throw new ServiceUnavailableException(e.getMessage(),
                        "Promotion service unreachable and graceful degradation is OFF. " +
                        "No charges made. Safe to retry.");
                }
                // Graceful degradation: continue without the discount.
                trace.degraded("2", "applyPromotion",
                    "service unavailable — proceeding without discount (graceful degradation)");
            } catch (Exception e) {
                trace.failed("2", "applyPromotion", e.getMessage());
                throw e;
            }
        } else {
            trace.skipped("2", "applyPromotion", "no promo code provided");
        }

        BigDecimal finalTotal = cart.getSubtotal()
            .subtract(cart.getCartPromotionDiscount())
            .subtract(promotion.discount())
            .max(BigDecimal.ZERO);

        // ── Step 3 ─ Reserve stock (SIDE EFFECT: reduces available units) ────────────
        // If DOWN here: fail immediately. Payment has not been attempted yet.
        InventoryReservation reservation;
        try {
            reservation = inventoryService.reserveStock(quantities);
            trace.ok("3", "reserveStock");
        } catch (Exception e) {
            trace.failed("3", "reserveStock", e.getMessage());
            throw e;
        }

        // ── Step 4 ─ Process payment (SIDE EFFECT: charges the card) ─────────────────
        // Runs on a separate thread with a hard deadline. On any failure (DOWN, timeout,
        // decline): COMPENSATION — release the reservation so stock returns to the pool.
        PaymentRequest paymentRequest = new PaymentRequest(
            request.getCardNumber(), request.getCardHolderName(),
            finalTotal, request.getIdempotencyKey()
        );
        PaymentResult payment;
        Future<PaymentResult> paymentFuture = paymentExecutor.submit(
            () -> paymentService.processPayment(paymentRequest)
        );
        try {
            payment = paymentFuture.get(timeoutMs, TimeUnit.MILLISECONDS);
            trace.ok("4", "processPayment → " + payment.paymentToken());
        } catch (TimeoutException e) {
            paymentFuture.cancel(true);
            inventoryService.releaseReservation(reservation);
            trace.failed("4", "processPayment", "timed out after " + timeoutMs + "ms");
            trace.compensated("releaseReservation", "payment timeout — stock returned to pool");
            throw new ServiceUnavailableException(
                "Payment service timed out after " + timeoutMs + "ms — increase paymentTimeoutMs or fix the slow service",
                "Inventory reservation released — stock returned to pool. No charge was made.");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            trace.failed("4", "processPayment", cause.getMessage());
            inventoryService.releaseReservation(reservation);
            trace.compensated("releaseReservation", "payment failure — stock returned to pool");
            if (cause instanceof PaymentFailedException pfe) throw pfe;
            if (cause instanceof ServiceUnavailableException sue) {
                throw new ServiceUnavailableException(sue.getMessage(),
                    "Inventory reservation released — stock returned to pool. No charge was made.");
            }
            throw new ServiceUnavailableException("Payment error: " + cause.getMessage(),
                "Inventory reservation released — stock returned to pool. No charge was made.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            inventoryService.releaseReservation(reservation);
            trace.compensated("releaseReservation", "thread interrupted");
            throw new ServiceUnavailableException("Payment interrupted");
        }

        // ── Step 5 ─ Persist the order (DB write) ────────────────────────────────────
        // On failure: COMPENSATION — refund the charge AND release the reservation.
        Order order;
        try {
            order = orderService.createOrder(cart, promotion, payment, finalTotal);
            trace.ok("5", "createOrder → #" + order.getId());
        } catch (Exception e) {
            paymentService.refund(payment.paymentToken());
            inventoryService.releaseReservation(reservation);
            trace.failed("5", "createOrder", e.getMessage());
            trace.compensated("refund", payment.paymentToken());
            trace.compensated("releaseReservation", "order persist failed — stock returned");
            throw new CheckoutException("Failed to persist order after payment", e);
        }

        // ── Step 6 ─ Commit reservation (makes the hold permanent) ───────────────────
        // Most complex compensation: order exists and payment settled — must undo both.
        try {
            inventoryService.commitReservation(reservation);
            trace.ok("6", "commitReservation");
        } catch (Exception e) {
            paymentService.refund(payment.paymentToken());
            orderService.cancelOrder(order);
            inventoryService.releaseReservation(reservation);
            trace.failed("6", "commitReservation", e.getMessage());
            trace.compensated("refund", payment.paymentToken());
            trace.compensated("cancelOrder", "#" + order.getId());
            trace.compensated("releaseReservation", "commit failed — full rollback");
            throw new CheckoutException("Failed to commit inventory after order was created", e);
        }

        return order;
    }
}
