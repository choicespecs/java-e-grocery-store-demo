package com.demo.grocery.repository;

import com.demo.grocery.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link com.demo.grocery.domain.Category}.
 *
 * <p>Provides standard CRUD operations inherited from {@link org.springframework.data.jpa.repository.JpaRepository}.
 * Used by {@link com.demo.grocery.service.ProductService#findAllCategories()} to populate
 * the navigation menu on every page (via {@link com.demo.grocery.controller.CartModelAdvice}).
 */
public interface CategoryRepository extends JpaRepository<Category, Long> {
}
