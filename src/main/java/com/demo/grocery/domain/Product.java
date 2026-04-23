package com.demo.grocery.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

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
    public boolean isInStock() { return stockQuantity > 0; }
    public boolean isLowStock() { return stockQuantity > 0 && stockQuantity <= 5; }
}
