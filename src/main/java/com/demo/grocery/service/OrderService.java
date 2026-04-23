package com.demo.grocery.service;

import com.demo.grocery.domain.Order;
import com.demo.grocery.domain.OrderItem;
import com.demo.grocery.domain.OrderStatus;
import com.demo.grocery.dto.Cart;
import com.demo.grocery.dto.CartItem;
import com.demo.grocery.external.payment.PaymentResult;
import com.demo.grocery.external.promotion.PromotionResult;
import com.demo.grocery.repository.OrderRepository;
import com.demo.grocery.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Handles the two @Transactional database operations in the checkout saga.
 *
 * <p>This class is deliberately separate from {@link CheckoutService} so that each DB
 * write carries its own transaction boundary without wrapping the entire multi-step saga
 * in a single long-running transaction.
 *
 * <h3>createOrder</h3>
 * Called at Step 5 of the saga (after payment succeeds). Writes:
 * <ul>
 *   <li>One {@code grocery_order} row with status {@code CONFIRMED} and the payment token.</li>
 *   <li>One {@code order_item} row per cart item — a snapshot, not a FK to {@code product}.</li>
 *   <li>Decrements {@code product.stock_quantity} for each item to keep the display cache
 *       roughly accurate (not the authoritative inventory counter).</li>
 * </ul>
 *
 * <h3>cancelOrder</h3>
 * Called as a compensating action at Step 6 if {@code commitReservation} fails.
 * Sets {@code order.status = CANCELLED}. Does NOT restore {@code product.stock_quantity} —
 * see CHAOS_ENGINEERING.md (Known Data Inconsistency After Rollback) for discussion.
 */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public Order createOrder(Cart cart, PromotionResult promotion, PaymentResult payment, BigDecimal total) {
        Order order = new Order();
        order.setStatus(OrderStatus.CONFIRMED);
        order.setSubtotal(cart.getSubtotal());
        order.setDiscount(promotion.discount());
        order.setTotal(total);
        order.setPromoCode(promotion.isNone() ? null : promotion.code());
        order.setPaymentToken(payment.paymentToken());

        for (CartItem cartItem : cart.getItems()) {
            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(order);
            orderItem.setProductId(cartItem.getProductId());
            orderItem.setProductName(cartItem.getProductName());
            orderItem.setUnitPrice(cartItem.getUnitPrice());
            orderItem.setQuantity(cartItem.getQuantity());
            order.getItems().add(orderItem);

            // Sync the locally-cached stock in the DB so the product listing stays accurate
            productRepository.decrementStock(cartItem.getProductId(), cartItem.getQuantity());
        }

        return orderRepository.save(order);
    }

    /**
     * Compensating action for Step 6 failure: marks the order as CANCELLED.
     * Called after the payment refund has been issued and before the inventory reservation
     * is released (compensation order: refund → cancelOrder → releaseReservation).
     */
    @Transactional
    public void cancelOrder(Order order) {
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);
    }

    public Optional<Order> findById(Long id) {
        return orderRepository.findById(id);
    }
}
