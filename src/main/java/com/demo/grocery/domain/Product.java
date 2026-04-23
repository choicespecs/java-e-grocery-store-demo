package com.demo.grocery.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * JPA entity representing a grocery product in the catalogue.
 *
 * <p><strong>Stock quantity note:</strong> {@code stockQuantity} is a <em>display cache</em>
 * only. The authoritative inventory counter lives in
 * {@link com.demo.grocery.external.inventory.stub.StubInventoryService#available}, which is
 * decremented at reservation time and restored on release. {@code stockQuantity} in the
 * database is only decremented after a confirmed order (via
 * {@link com.demo.grocery.repository.ProductRepository#decrementStock}) and is never restored
 * by the compensation path — a known display inconsistency documented in CHAOS_ENGINEERING.md.
 *
 * <p>Products that are seeded with {@code stockQuantity = 0} (e.g. Free Range Eggs) display
 * an "Out of Stock" badge and cannot be added to the cart. Products with
 * {@code stockQuantity <= 5} display a "Low Stock" badge.
 */
@Entity
@Table(name = "product")
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "category_id")
    private Category category;

    private BigDecimal price;

    // Locally cached stock level — the authoritative source is StubInventoryService.
    // Updated after each successful checkout to keep the UI roughly accurate.
    private int stockQuantity;

    protected Product() {}

    public Product(String name, Category category, BigDecimal price, int stockQuantity) {
        this.name = name;
        this.category = category;
        this.price = price;
        this.stockQuantity = stockQuantity;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public Category getCategory() { return category; }
    public BigDecimal getPrice() { return price; }
    public int getStockQuantity() { return stockQuantity; }
    public void setStockQuantity(int stockQuantity) { this.stockQuantity = stockQuantity; }

    /** Returns true if at least one unit is available for display purposes. */
    public boolean isInStock() { return stockQuantity > 0; }

    /** Returns true if the display stock is between 1 and 5 inclusive — triggers a "Low Stock" badge. */
    public boolean isLowStock() { return stockQuantity > 0 && stockQuantity <= 5; }
}
