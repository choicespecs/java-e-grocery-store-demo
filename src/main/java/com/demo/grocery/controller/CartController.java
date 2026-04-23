package com.demo.grocery.controller;

import com.demo.grocery.domain.Product;
import com.demo.grocery.dto.Cart;
import com.demo.grocery.dto.CartItem;
import com.demo.grocery.external.cartpromotion.CartPromotionService;
import com.demo.grocery.repository.ProductRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Map;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/cart")
public class CartController {

    private final Cart cart;
    private final ProductRepository productRepository;
    private final CartPromotionService cartPromotionService;

    public CartController(Cart cart, ProductRepository productRepository,
                          CartPromotionService cartPromotionService) {
        this.cart = cart;
        this.productRepository = productRepository;
        this.cartPromotionService = cartPromotionService;
    }

    @GetMapping
    public String viewCart(Model model) {
        model.addAttribute("cart", cart);
        Map<Long, Integer> stockMap = cart.getItems().stream()
            .collect(Collectors.toMap(
                CartItem::getProductId,
                item -> productRepository.findById(item.getProductId())
                    .map(Product::getStockQuantity).orElse(0)
            ));
        model.addAttribute("stockMap", stockMap);
        return "cart";
    }

    @PostMapping("/add")
    public String addItem(@RequestParam Long productId,
                          @RequestParam(defaultValue = "1") int quantity,
                          RedirectAttributes redirectAttrs) {
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> new IllegalArgumentException("Unknown product: " + productId));

        if (!product.isInStock()) {
            redirectAttrs.addFlashAttribute("error", product.getName() + " is out of stock.");
            return "redirect:/";
        }

        int alreadyInCart = cart.getItems().stream()
            .filter(i -> i.getProductId().equals(productId))
            .mapToInt(i -> i.getQuantity())
            .findFirst().orElse(0);
        int available = product.getStockQuantity() - alreadyInCart;
        if (available <= 0) {
            redirectAttrs.addFlashAttribute("error",
                "You already have all available stock of " + product.getName() + " in your cart.");
            return "redirect:/";
        }
        int allowedQty = Math.min(quantity, available);
        cart.addItem(product, allowedQty);
        cart.applyCartPromotion(cartPromotionService.evaluate(cart.getItems()));
        if (allowedQty < quantity) {
            redirectAttrs.addFlashAttribute("warning",
                "Only " + allowedQty + " more " + product.getName() + " added — that's all that's available.");
        } else {
            redirectAttrs.addFlashAttribute("success", product.getName() + " added to cart.");
        }
        return "redirect:/";
    }

    @PostMapping("/update")
    public String updateQuantity(@RequestParam Long productId, @RequestParam int quantity,
                                 RedirectAttributes redirectAttrs) {
        if (quantity > 0) {
            Product product = productRepository.findById(productId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown product: " + productId));
            int capped = Math.min(quantity, product.getStockQuantity());
            cart.updateQuantity(productId, capped);
        } else {
            cart.updateQuantity(productId, quantity);
        }
        cart.applyCartPromotion(cartPromotionService.evaluate(cart.getItems()));
        return "redirect:/cart";
    }

    @PostMapping("/remove")
    public String removeItem(@RequestParam Long productId) {
        cart.removeItem(productId);
        cart.applyCartPromotion(cartPromotionService.evaluate(cart.getItems()));
        return "redirect:/cart";
    }

    @PostMapping("/clear")
    public String clearCart() {
        cart.clear(); // also resets cartPromotion to none()
        return "redirect:/cart";
    }
}
