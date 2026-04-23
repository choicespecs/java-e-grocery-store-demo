package com.demo.grocery.controller;

import com.demo.grocery.demo.SagaTrace;
import com.demo.grocery.domain.Order;
import com.demo.grocery.dto.Cart;
import com.demo.grocery.dto.CheckoutRequest;
import com.demo.grocery.exception.CheckoutException;
import com.demo.grocery.exception.InsufficientStockException;
import com.demo.grocery.exception.InvalidPromotionException;
import com.demo.grocery.exception.PaymentFailedException;
import com.demo.grocery.exception.ServiceUnavailableException;
import com.demo.grocery.service.CheckoutService;
import com.demo.grocery.service.OrderService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Controller
@RequestMapping("/checkout")
public class CheckoutController {

    private final CheckoutService checkoutService;
    private final OrderService orderService;
    private final Cart cart;

    public CheckoutController(CheckoutService checkoutService, OrderService orderService, Cart cart) {
        this.checkoutService = checkoutService;
        this.orderService    = orderService;
        this.cart            = cart;
    }

    @GetMapping
    public String checkoutForm(Model model) {
        if (cart.isEmpty()) {
            return "redirect:/cart";
        }
        CheckoutRequest request = new CheckoutRequest();
        // A fresh UUID per form load acts as an idempotency key: the payment service
        // deduplicates retries with the same key, preventing double-charges on network
        // hiccups or accidental double-submits.
        request.setIdempotencyKey(UUID.randomUUID().toString());
        model.addAttribute("cart", cart);
        model.addAttribute("request", request);
        return "checkout";
    }

    @PostMapping
    public String processCheckout(@ModelAttribute("request") CheckoutRequest request, Model model) {
        if (cart.isEmpty()) {
            return "redirect:/cart";
        }

        SagaTrace trace = new SagaTrace();

        try {
            Order order = checkoutService.checkout(cart, request, trace);
            cart.clear();
            return "redirect:/checkout/confirmation/" + order.getId();

        } catch (InsufficientStockException e) {
            model.addAttribute("error", "Some items are no longer available: " + e.getMessage());
            model.addAttribute("sagaTrace", trace.getSteps());
            model.addAttribute("cart", cart);
            model.addAttribute("request", request);
            return "checkout";

        } catch (InvalidPromotionException e) {
            model.addAttribute("promoError", e.getMessage());
            model.addAttribute("sagaTrace", trace.getSteps());
            model.addAttribute("cart", cart);
            model.addAttribute("request", request);
            return "checkout";

        } catch (PaymentFailedException e) {
            model.addAttribute("error", "Payment failed: " + e.getMessage());
            model.addAttribute("rollbackNote",
                "Your cart is intact. The inventory reservation has been released. No charge was made.");
            model.addAttribute("sagaTrace", trace.getSteps());
            model.addAttribute("cart", cart);
            model.addAttribute("request", request);
            return "checkout";

        } catch (ServiceUnavailableException e) {
            model.addAttribute("error", "Service unavailable: " + e.getMessage());
            model.addAttribute("rollbackNote", e.hasCompensationNote()
                ? e.getCompensationNote()
                : "Your cart is intact. No charges were made. Safe to retry.");
            model.addAttribute("sagaTrace", trace.getSteps());
            model.addAttribute("cart", cart);
            model.addAttribute("request", request);
            return "checkout";

        } catch (CheckoutException e) {
            model.addAttribute("error", "Checkout failed: " + e.getMessage());
            model.addAttribute("rollbackNote",
                "All compensating actions have been applied: any payment was refunded and inventory was released.");
            model.addAttribute("sagaTrace", trace.getSteps());
            model.addAttribute("cart", cart);
            model.addAttribute("request", request);
            return "checkout";
        }
    }

    @GetMapping("/confirmation/{id}")
    public String confirmation(@PathVariable Long id, Model model) {
        Order order = orderService.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Order not found: " + id));
        model.addAttribute("order", order);
        return "confirmation";
    }
}
