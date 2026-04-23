package com.demo.grocery.controller;

import com.demo.grocery.demo.DemoFaultConfig;
import com.demo.grocery.demo.FaultMode;
import com.demo.grocery.demo.SagaTrace;
import com.demo.grocery.domain.Order;
import com.demo.grocery.dto.Cart;
import com.demo.grocery.dto.CheckoutRequest;
import com.demo.grocery.exception.ServiceUnavailableException;
import com.demo.grocery.external.coupon.CouponResult;
import com.demo.grocery.external.coupon.CouponService;
import com.demo.grocery.service.CheckoutService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.UUID;

@Controller
@RequestMapping("/demo")
public class DemoController {

    private final DemoFaultConfig faultConfig;
    private final Cart cart;
    private final CheckoutService checkoutService;
    private final CouponService couponService;

    public DemoController(DemoFaultConfig faultConfig, Cart cart,
                          CheckoutService checkoutService, CouponService couponService) {
        this.faultConfig = faultConfig;
        this.cart = cart;
        this.checkoutService = checkoutService;
        this.couponService = couponService;
    }

    @GetMapping
    public String showPanel(Model model) {
        model.addAttribute("config", faultConfig);
        model.addAttribute("modes", FaultMode.values());
        return "demo";
    }

    @PostMapping
    public String updateConfig(
            @RequestParam FaultMode inventoryCheckMode,
            @RequestParam FaultMode inventoryReserveMode,
            @RequestParam FaultMode inventoryCommitMode,
            @RequestParam FaultMode paymentMode,
            @RequestParam(defaultValue = "false") boolean paymentIdempotencyEnabled,
            @RequestParam FaultMode promotionMode,
            @RequestParam(defaultValue = "false") boolean promotionGracefulDegradation,
            @RequestParam FaultMode couponMode,
            @RequestParam int slowDelayMs,
            @RequestParam int paymentTimeoutMs,
            RedirectAttributes ra) {

        faultConfig.setInventoryCheckMode(inventoryCheckMode);
        faultConfig.setInventoryReserveMode(inventoryReserveMode);
        faultConfig.setInventoryCommitMode(inventoryCommitMode);
        faultConfig.setPaymentMode(paymentMode);
        faultConfig.setPaymentIdempotencyEnabled(paymentIdempotencyEnabled);
        faultConfig.setPromotionMode(promotionMode);
        faultConfig.setPromotionGracefulDegradation(promotionGracefulDegradation);
        faultConfig.setCouponMode(couponMode);
        faultConfig.setSlowDelayMs(slowDelayMs);
        faultConfig.setPaymentTimeoutMs(paymentTimeoutMs);

        ra.addFlashAttribute("success", "Fault configuration updated.");
        return "redirect:/demo";
    }

    @PostMapping("/preset")
    public String applyPreset(@RequestParam String scenario, RedirectAttributes ra) {
        faultConfig.resetAll();
        String description = switch (scenario) {
            case "all-normal" -> {
                yield "All services normal — happy path.";
            }
            case "payment-down" -> {
                faultConfig.setPaymentMode(FaultMode.DOWN);
                yield "Payment service DOWN — inventory will be reserved then released (compensated).";
            }
            case "payment-slow" -> {
                // slowDelayMs (2s) < paymentTimeoutMs (3s) → slow but succeeds
                faultConfig.setPaymentMode(FaultMode.SLOW);
                faultConfig.setSlowDelayMs(2000);
                faultConfig.setPaymentTimeoutMs(3000);
                yield "Payment SLOW (2s delay, 3s timeout) — succeeds after 2 seconds.";
            }
            case "payment-timeout" -> {
                // slowDelayMs (5s) > paymentTimeoutMs (3s) → triggers timeout + rollback
                faultConfig.setPaymentMode(FaultMode.SLOW);
                faultConfig.setSlowDelayMs(5000);
                faultConfig.setPaymentTimeoutMs(3000);
                yield "Payment TIMEOUT (5s delay, 3s timeout) — checkout times out and releases the inventory reservation.";
            }
            case "payment-flaky" -> {
                faultConfig.setPaymentMode(FaultMode.FLAKY);
                yield "Payment service FLAKY — every other checkout attempt will fail.";
            }
            case "payment-no-idempotency" -> {
                faultConfig.setPaymentIdempotencyEnabled(false);
                yield "Idempotency OFF — refreshing/resubmitting the checkout page will double-charge.";
            }
            case "inventory-check-down" -> {
                faultConfig.setInventoryCheckMode(FaultMode.DOWN);
                yield "Inventory DOWN at pre-flight check — checkout fails immediately, no side effects.";
            }
            case "inventory-reserve-down" -> {
                faultConfig.setInventoryReserveMode(FaultMode.DOWN);
                yield "Inventory DOWN at reserve — fails after availability check, before any payment.";
            }
            case "inventory-commit-down" -> {
                faultConfig.setInventoryCommitMode(FaultMode.DOWN);
                yield "Inventory DOWN at commit — payment charged + order saved, then full saga rollback.";
            }
            case "inventory-check-flaky" -> {
                faultConfig.setInventoryCheckMode(FaultMode.FLAKY);
                yield "Inventory FLAKY at availability check (Step 1) — every other checkout fails at the read-only pre-flight. No side effects on failure; safe to retry.";
            }
            case "inventory-check-slow" -> {
                faultConfig.setInventoryCheckMode(FaultMode.SLOW);
                faultConfig.setSlowDelayMs(3000);
                yield "Inventory SLOW at availability check (3s, Step 1) — request thread blocks on the read-only pre-flight. No timeout: demonstrates why every external call needs a deadline, not just payment.";
            }
            case "inventory-reserve-flaky" -> {
                faultConfig.setInventoryReserveMode(FaultMode.FLAKY);
                yield "Inventory FLAKY at reserve (Step 3) — every other call fails before payment is attempted. No charge made; safe to retry.";
            }
            case "inventory-reserve-slow" -> {
                faultConfig.setInventoryReserveMode(FaultMode.SLOW);
                faultConfig.setSlowDelayMs(4000);
                yield "Inventory SLOW at reserve (4 s) — the request thread blocks for 4 seconds. Unlike payment, there is no dedicated thread or timeout protecting this call. Demonstrates why every external call needs a deadline, not just payment.";
            }
            case "inventory-commit-slow" -> {
                faultConfig.setInventoryCommitMode(FaultMode.SLOW);
                faultConfig.setSlowDelayMs(3000);
                yield "Inventory SLOW at commit (3s, Step 6) — payment is already settled before this step. Succeeds after delay, but highlights that commit has no timeout and a crashed service here would require the full 3-way compensation.";
            }
            case "inventory-commit-flaky" -> {
                faultConfig.setInventoryCommitMode(FaultMode.FLAKY);
                yield "Inventory FLAKY at commit (Step 6) — every other call triggers full 3-way rollback: refund + cancelOrder + releaseReservation.";
            }
            case "coupon-slow" -> {
                faultConfig.setCouponMode(FaultMode.SLOW);
                faultConfig.setSlowDelayMs(3000);
                yield "Coupon engine SLOW (3s) — every cart add/update/remove blocks the request thread for 3 seconds. Like inventory reserve, there is no timeout protecting this call.";
            }
            case "promotion-slow" -> {
                faultConfig.setPromotionMode(FaultMode.SLOW);
                faultConfig.setPromotionGracefulDegradation(true);
                faultConfig.setSlowDelayMs(3000);
                yield "Promotion SLOW (3s) + graceful degradation ON — Step 2 blocks the checkout request thread for 3 seconds. No timeout on this call either; succeeds after the delay.";
            }
            case "promotion-down-graceful" -> {
                faultConfig.setPromotionMode(FaultMode.DOWN);
                faultConfig.setPromotionGracefulDegradation(true);
                yield "Promotion service DOWN, graceful degradation ON — checkout proceeds, promo skipped.";
            }
            case "promotion-down-hard" -> {
                faultConfig.setPromotionMode(FaultMode.DOWN);
                faultConfig.setPromotionGracefulDegradation(false);
                yield "Promotion service DOWN, graceful degradation OFF — checkout aborted.";
            }
            case "promotion-flaky" -> {
                faultConfig.setPromotionMode(FaultMode.FLAKY);
                faultConfig.setPromotionGracefulDegradation(true);
                yield "Promotion FLAKY + graceful degradation ON — every other promo-code lookup fails silently; checkout continues at full price. Safe to retry: promotion is read-only, so no compensation is ever needed.";
            }
            case "coupon-down" -> {
                faultConfig.setCouponMode(FaultMode.DOWN);
                yield "Coupon engine DOWN — coupon offers (BOGO, bundles, spend thresholds) are not applied. Quantity-based cart deals (Buy 2 Get 1, etc.) come from a separate service and remain active.";
            }
            case "coupon-flaky" -> {
                faultConfig.setCouponMode(FaultMode.FLAKY);
                yield "Coupon engine FLAKY — coupon offers apply on odd calls and are dropped on even calls. Quantity-based cart deals are unaffected.";
            }
            default -> "Unknown preset.";
        };
        ra.addFlashAttribute("success", description);
        return "redirect:/demo";
    }

    /**
     * Fires a test checkout using whatever is currently in the session cart and the
     * success card 4111-1111-1111-1111.  Returns the saga trace inline so the result
     * is visible immediately on the panel — no need to navigate to /checkout.
     */
    @PostMapping("/run")
    public String runTest(Model model) {
        if (cart.isEmpty()) {
            model.addAttribute("testError", "Your cart is empty — add at least one item from the store first.");
            model.addAttribute("config", faultConfig);
            model.addAttribute("modes", FaultMode.values());
            return "demo";
        }

        // Re-evaluate coupons against the current fault state so the test reflects
        // any coupon-engine preset applied since the items were last added to the cart.
        try {
            cart.applyCoupons(couponService.evaluate(cart.getItems()));
        } catch (ServiceUnavailableException e) {
            cart.applyCoupons(CouponResult.none());
        }

        CheckoutRequest request = new CheckoutRequest();
        request.setCardNumber("4111111111111111");
        request.setCardHolderName("Demo Tester");
        request.setExpiryMonth("12");
        request.setExpiryYear("2099");
        request.setCvv("123");
        request.setIdempotencyKey(UUID.randomUUID().toString());
        // Always include a promo code so the promotion service is exercised on every
        // test run — without one, step 2 is unconditionally skipped and promotion
        // faults can never fire.
        request.setPromoCode("SAVE10");

        SagaTrace trace = new SagaTrace();
        boolean succeeded = false;
        String outcomeMessage;

        try {
            Order order = checkoutService.checkout(cart, request, trace);
            cart.clear();
            succeeded = true;
            outcomeMessage = "Checkout succeeded — Order #" + order.getId() + " created.";
        } catch (Exception e) {
            outcomeMessage = e.getMessage();
        }

        model.addAttribute("testTrace", trace.getSteps());
        model.addAttribute("testOutcome", outcomeMessage);
        model.addAttribute("testSucceeded", succeeded);
        model.addAttribute("config", faultConfig);
        model.addAttribute("modes", FaultMode.values());
        return "demo";
    }
}
