package com.demo.grocery.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * JPA entity representing a single line item within an {@link Order}.
 *
 * {@code OrderItem} stores a point-in-time snapshot of the product that was purchased —
 * name, unit price, and quantity — rather than a live foreign key to the {@code Product}
 * table. This means the order record remains accurate even if the product is later
 * renamed, repriced, or deleted from the catalogue.
 *
 * <p>{@code productId} is stored as a plain column (not a JPA {@code @ManyToOne}) so that
 * historical order queries on a product ID still work, but no referential integrity
 * constraint prevents product deletion.
 */
@Entity
@Table(name = "order_item")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private Order order;

    // Snapshot of product data at time of purchase — not a FK so the order survives product changes
    private Long productId;
    private String productName;
    private BigDecimal unitPrice;
    private int quantity;

    public Long getId() { return id; }
    public Order getOrder() { return order; }
    public void setOrder(Order order) { this.order = order; }
    public Long getProductId() { return productId; }
    public void setProductId(Long productId) { this.productId = productId; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal unitPrice) { this.unitPrice = unitPrice; }
    public int getQuantity() { return quantity; }
    public void setQuantity(int quantity) { this.quantity = quantity; }

    /** Returns the total cost for this line: unitPrice × quantity. */
    public BigDecimal getLineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
