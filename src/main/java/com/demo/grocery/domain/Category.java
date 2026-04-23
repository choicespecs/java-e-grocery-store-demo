package com.demo.grocery.domain;

import jakarta.persistence.*;

/**
 * JPA entity representing a top-level product category (e.g. Produce, Dairy, Bakery, Beverages).
 *
 * Categories are seeded by {@link com.demo.grocery.config.DataInitializer} on every startup.
 * They are read-only during normal operation and serve as the primary filter on the product
 * catalogue page.
 */
@Entity
@Table(name = "category")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    protected Category() {}

    public Category(String name) {
        this.name = name;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
}
