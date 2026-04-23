package com.demo.grocery.service;

import com.demo.grocery.domain.Category;
import com.demo.grocery.domain.Product;
import com.demo.grocery.repository.CategoryRepository;
import com.demo.grocery.repository.ProductRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;

    public ProductService(ProductRepository productRepository, CategoryRepository categoryRepository) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
    }

    public List<Product> findAll() {
        return productRepository.findAll();
    }

    public List<Product> findByCategory(String categoryName) {
        return productRepository.findByCategoryName(categoryName);
    }

    public Optional<Product> findById(Long id) {
        return productRepository.findById(id);
    }

    public List<Category> findAllCategories() {
        return categoryRepository.findAll();
    }
}
