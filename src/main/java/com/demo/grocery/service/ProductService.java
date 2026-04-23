package com.demo.grocery.service;

import com.demo.grocery.domain.Category;
import com.demo.grocery.domain.Product;
import com.demo.grocery.repository.CategoryRepository;
import com.demo.grocery.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Read-only service layer over {@link ProductRepository} and {@link CategoryRepository}.
 *
 * <p>Provides the product catalogue data used by {@link com.demo.grocery.controller.ProductController}
 * for the storefront and by {@link com.demo.grocery.controller.CartModelAdvice} to populate
 * the category navigation on every page.
 *
 * <p>This service does not participate in the checkout saga. It has no write operations
 * and no dependency on {@link com.demo.grocery.demo.DemoFaultConfig}.
 */
@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    public ProductService(ProductRepository productRepository, CategoryRepository categoryRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
    }

    /** Returns all products across all categories. */
    public List<Product> findAll() {
        return productRepository.findAll();
    }

    /** Returns all products belonging to the named category. */
    public List<Product> findByCategory(String categoryName) {
        return productRepository.findByCategoryName(categoryName);
    }

    /** Returns a single product by ID, or empty if not found. */
    public Optional<Product> findById(Long id) {
        return productRepository.findById(id);
    }

    /** Returns all categories for the navigation menu. */
    public List<Category> findAllCategories() {
        return categoryRepository.findAll();
    }
}
