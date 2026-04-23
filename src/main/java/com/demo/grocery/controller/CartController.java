package com.demo.grocery.controller;

import com.demo.grocery.domain.Product;
import com.demo.grocery.dto.Cart;
import com.demo.grocery.dto.CartItem;
import com.demo.grocery.exception.ServiceUnavailableException;
import com.demo.grocery.external.cartpromotion.CartDeal;
import com.demo.grocery.external.cartpromotion.CartPromotionService;
import com.demo.grocery.external.coupon.CouponOffer;
import com.demo.grocery.external.coupon.CouponResult;
import com.demo.grocery.external.coupon.CouponService;
import com.demo.grocery.repository.ProductRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Handles all cart operations: view, add, update, remove, and clear.
 *
 * <p>Route summary:
 * <ul>
 *   <li>{@code GET  /cart}        — renders the cart page; refreshes discounts on every load.</li>
 *   <li>{@code POST /cart/add}    — adds a product (capped to available stock); redirects to {@code /}.</li>
 *   <li>{@code POST /cart/update} — updates quantity for an existing item; redirects to {@code /cart}.</li>
 *   <li>{@code POST /cart/remove} — removes an item; redirects to {@code /cart}.</li>
 *   <li>{@code POST /cart/clear}  — empties the cart; redirects to {@code /cart}.</li>
 * </ul>
 *
 * <p>Every mutation calls {@link #refreshDiscounts()} which re-evaluates both discount streams.
 * The cart-promotion stream never faults; the coupon stream is gracefully degraded on failure —
 * the cart continues with zero coupon discount and a {@code couponServiceDown} flag for the UI.
 *
 * <p>Quantities are capped to {@code product.getStockQuantity()} (the display cache) so
 * users cannot request more units than are shown as available.
 */
@Controller
@RequestMapping("/cart")
public class CartController {

    private final Cart cart;
    private final ProductRepository productRepository;
    private final CartPromotionService cartPromotionService;
    private final CouponService couponService;

    public CartController(Cart cart, ProductRepository productRepository,
                          CartPromotionService cartPromotionService,
                          CouponService couponService) {
        this.cart = cart;
        this.productRepository = productRepository;
        this.cartPromotionService = cartPromotionService;
        this.couponService = couponService;
    }

    @GetMapping
    public String viewCart(Model model) {
        // Re-evaluate discounts on every page load so fault config changes
        // are reflected immediately without needing a cart mutation.
        if (!cart.isEmpty()) {
            refreshDiscounts();
        }

        model.addAttribute("cart", cart);

        Map<Long, Integer> stockMap = cart.getItems().stream()
            .collect(Collectors.toMap(
                CartItem::getProductId,
                item -> productRepository.findById(item.getProductId())
                    .map(Product::getStockQuantity).orElse(0)
            ));
        model.addAttribute("stockMap", stockMap);

        // Cart deal status: catalog + which are applied + quantities in cart
        model.addAttribute("cartDealHints", cartPromotionService.getAvailableDeals());
        Set<String> appliedDealProducts = cart.getCartPromotion().deals().stream()
            .map(CartDeal::productName).collect(Collectors.toSet());
        Map<String, BigDecimal> appliedDealDiscounts = cart.getCartPromotion().deals().stream()
            .collect(Collectors.toMap(CartDeal::productName, CartDeal::discount));
        Map<String, Integer> cartNameToQty = cart.getItems().stream()
            .collect(Collectors.toMap(CartItem::getProductName, CartItem::getQuantity));
        model.addAttribute("appliedDealProducts", appliedDealProducts);
        model.addAttribute("appliedDealDiscounts", appliedDealDiscounts);
        model.addAttribute("cartNameToQty", cartNameToQty);

        // Coupon offer status: catalog + which are applied + service health flag
        model.addAttribute("couponCatalog", couponService.getOfferCatalog());
        Set<String> appliedOfferIds = cart.getCouponResult().offers().stream()
            .map(CouponOffer::offerId).collect(Collectors.toSet());
        Map<String, BigDecimal> appliedOfferDiscounts = cart.getCouponResult().offers().stream()
            .collect(Collectors.toMap(CouponOffer::offerId, CouponOffer::discount));
        model.addAttribute("appliedOfferIds", appliedOfferIds);
        model.addAttribute("appliedOfferDiscounts", appliedOfferDiscounts);
        model.addAttribute("couponServiceDown", cart.isCouponServiceDown());

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
            .mapToInt(CartItem::getQuantity)
            .findFirst().orElse(0);
        int available = product.getStockQuantity() - alreadyInCart;
        if (available <= 0) {
            redirectAttrs.addFlashAttribute("error",
                "You already have all available stock of " + product.getName() + " in your cart.");
            return "redirect:/";
        }
        int allowedQty = Math.min(quantity, available);
        cart.addItem(product, allowedQty);
        refreshDiscounts();
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
        refreshDiscounts();
        return "redirect:/cart";
    }

    @PostMapping("/remove")
    public String removeItem(@RequestParam Long productId) {
        cart.removeItem(productId);
        refreshDiscounts();
        return "redirect:/cart";
    }

    @PostMapping("/clear")
    public String clearCart() {
        cart.clear();
        return "redirect:/cart";
    }

    /**
     * Re-evaluates both discount streams after every cart mutation (and on viewCart).
     * The coupon engine is non-critical: failures clear the offers and set the
     * couponServiceDown flag so the UI can display a clear "engine unavailable" warning.
     */
    private void refreshDiscounts() {
        cart.applyCartPromotion(cartPromotionService.evaluate(cart.getItems()));
        try {
            cart.applyCoupons(couponService.evaluate(cart.getItems()));
            cart.setCouponServiceDown(false);
        } catch (ServiceUnavailableException e) {
            cart.applyCoupons(CouponResult.none());
            cart.setCouponServiceDown(true);
        }
    }
}
