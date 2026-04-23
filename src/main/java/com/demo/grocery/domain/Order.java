package com.demo.grocery.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * JPA entity representing a completed (or cancelled) customer order.
 *
 * An {@code Order} is created at Step 5 of the checkout saga by
 * {@link com.demo.grocery.service.OrderService#createOrder} with status {@code CONFIRMED}.
 * If the final saga step (Step 6 — commit reservation) fails, the order is updated to
 * {@code CANCELLED} by {@link com.demo.grocery.service.OrderService#cancelOrder} as a
 * compensating action.
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code subtotal}     — sum of all line items before any discount</li>
 *   <li>{@code discount}     — promo-code discount only; cart-promotion and coupon discounts
 *                              are already reflected in the {@code total}</li>
 *   <li>{@code total}        — the amount actually charged to the payment service</li>
 *   <li>{@code promoCode}    — nullable; set only if a valid promo code was applied</li>
 *   <li>{@code paymentToken} — opaque token from {@link com.demo.grocery.external.payment.PaymentService};
 *                              used to issue a refund if a later step fails</li>
 * </ul>
 *
 * <p>The table is named {@code grocery_order} to avoid a conflict with the SQL reserved word
 * {@code ORDER}.
 */
@Entity
@Table(name = "grocery_order")
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<OrderItem> items = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    private BigDecimal subtotal;
    private BigDecimal discount;
    private BigDecimal total;
    private String promoCode;
    private String paymentToken;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public List<OrderItem> getItems() { return items; }
    public OrderStatus getStatus() { return status; }
    public void setStatus(OrderStatus status) { this.status = status; }
    public BigDecimal getSubtotal() { return subtotal; }
    public void setSubtotal(BigDecimal subtotal) { this.subtotal = subtotal; }
    public BigDecimal getDiscount() { return discount; }
    public void setDiscount(BigDecimal discount) { this.discount = discount; }
    public BigDecimal getTotal() { return total; }
    public void setTotal(BigDecimal total) { this.total = total; }
    public String getPromoCode() { return promoCode; }
    public void setPromoCode(String promoCode) { this.promoCode = promoCode; }
    public String getPaymentToken() { return paymentToken; }
    public void setPaymentToken(String paymentToken) { this.paymentToken = paymentToken; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public boolean hasDiscount() { return discount != null && discount.compareTo(BigDecimal.ZERO) > 0; }
}
