package com.demo.grocery.controller;

import com.demo.grocery.domain.Product;
import com.demo.grocery.dto.Cart;
import com.demo.grocery.external.cartpromotion.CartPromotionService;
import com.demo.grocery.service.ProductService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;

@Controller
public class ProductController {

    private final ProductService productService;
    private final CartPromotionService cartPromotionService;
    private final Cart cart;

    public ProductController(ProductService productService, CartPromotionService cartPromotionService, Cart cart) {
        this.productService = productService;
        this.cartPromotionService = cartPromotionService;
        this.cart = cart;
    }

    @GetMapping("/")
    public String index(@RequestParam(required = false) String category, Model model) {
        List<Product> products = (category != null && !category.isBlank())
            ? productService.findByCategory(category)
            : productService.findAll();

        model.addAttribute("products", products);
        model.addAttribute("selectedCategory", category);
        model.addAttribute("cartDeals", cartPromotionService.getAvailableDeals());
        model.addAttribute("cartQtyMap", cart.getQuantityMap());
        return "index";
    }
}
