package com.demo.grocery.dto;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * A price-locked snapshot of a product at the time it was added to the cart.
 *
 * <p>The {@code unitPrice} is captured at add-time and never updated, even if the
 * product's price changes mid-session. This prevents cart totals from shifting while
 * the customer is browsing.
 *
 * <p>Both {@code CartItem} and its parent {@link Cart} implement {@link java.io.Serializable}
 * so they can survive session persistence (e.g. if the servlet container serializes sessions
 * to disk). No sensitive data (card numbers, CVVs) is stored here.
 *
 * <p>Instances are held in {@link Cart#items} keyed by {@code productId}. Adding the same
 * product twice merges the quantities rather than creating a duplicate entry.
 */
public class CartItem implements Serializable {

    private Long productId;
    private String productName;
    private BigDecimal unitPrice;
    private int quantity;

    public CartItem(Long productId, String productName, BigDecimal unitPrice, int quantity) {
        this.productId = productId;
        this.productName = productName;
        this.unitPrice = unitPrice;
        this.quantity = quantity;
    }

    public Long getProductId() { return productId; }
    public String getProductName() { return productName; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    public BigDecimal getLineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
