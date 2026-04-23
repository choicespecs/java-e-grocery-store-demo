package com.demo.grocery.controller;

import com.demo.grocery.domain.Category;
import com.demo.grocery.dto.Cart;
import com.demo.grocery.service.ProductService;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

import java.util.List;

/** Injects cart count and category list into every template automatically. */
@ControllerAdvice
public class CartModelAdvice {

    private final Cart cart;
    private final ProductService productService;

    public CartModelAdvice(Cart cart, ProductService productService) {
        this.cart = cart;
        this.productService = productService;
    }

    @ModelAttribute("cartItemCount")
    public int cartItemCount() {
        return cart.getTotalItems();
    }

    @ModelAttribute("categories")
    public List<Category> categories() {
        return productService.findAllCategories();
    }
}
