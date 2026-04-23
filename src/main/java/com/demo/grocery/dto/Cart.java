package com.demo.grocery.dto;

import com.demo.grocery.domain.Product;
import com.demo.grocery.external.cartpromotion.CartPromotionResult;
import com.demo.grocery.external.coupon.CouponResult;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Session-scoped shopping cart — one instance per HTTP session.
 *
 * <p>Spring creates this bean per session via a CGLIB proxy. Controllers inject the proxy
 * directly; services receive it as a method parameter so no singleton bean holds a
 * reference to session state.
 *
 * <p>The cart holds two independent discount streams that are re-evaluated on every mutation:
 * <ul>
 *   <li>{@code cartPromotion} — automatic quantity-based deals from
 *       {@link com.demo.grocery.external.cartpromotion.CartPromotionService}. Never faulted.</li>
 *   <li>{@code couponResult} — BOGO, bundle, and spend-threshold offers from
 *       {@link com.demo.grocery.external.coupon.CouponService}. Faultable; failures clear offers
 *       and set {@code couponServiceDown} so the UI shows a degraded state.</li>
 * </ul>
 *
 * <p>{@link #getEffectiveTotal()} returns {@code subtotal - cartPromoDiscount - couponDiscount}
 * and is used for display. {@code CheckoutService} subtracts a third tier — the promo-code
 * discount from {@code PromotionService} — at checkout time to produce the final charged amount.
 *
 * <p>Implements {@link java.io.Serializable} so the session can be persisted by the servlet
 * container without losing the cart contents.
 */
@Component
@SessionScope
public class Cart implements Serializable {

    private final Map<Long, CartItem> items = new LinkedHashMap<>();
    private CartPromotionResult cartPromotion = CartPromotionResult.none();
    private CouponResult couponResult = CouponResult.none();
    private boolean couponServiceDown = false;

    public void addItem(Product product, int quantity) {
        items.merge(
            product.getId(),
            new CartItem(product.getId(), product.getName(), product.getPrice(), quantity),
            (existing, incoming) -> {
                existing.setQuantity(existing.getQuantity() + incoming.getQuantity());
                return existing;
            }
        );
    }

    public void updateQuantity(Long productId, int quantity) {
        if (quantity <= 0) {
            items.remove(productId);
        } else {
            CartItem item = items.get(productId);
            if (item != null) item.setQuantity(quantity);
        }
    }

    public void removeItem(Long productId) {
        items.remove(productId);
    }

    public void applyCartPromotion(CartPromotionResult result) {
        this.cartPromotion = result;
    }

    public CartPromotionResult getCartPromotion() {
        return cartPromotion;
    }

    public void applyCoupons(CouponResult result) {
        this.couponResult = result;
    }

    public CouponResult getCouponResult() {
        return couponResult;
    }

    public BigDecimal getCouponDiscount() {
        return couponResult.totalDiscount();
    }

    public boolean isCouponServiceDown() {
        return couponServiceDown;
    }

    public void setCouponServiceDown(boolean couponServiceDown) {
        this.couponServiceDown = couponServiceDown;
    }

    public void clear() {
        items.clear();
        cartPromotion = CartPromotionResult.none();
        couponResult = CouponResult.none();
        couponServiceDown = false;
    }

    public Collection<CartItem> getItems() {
        return items.values();
    }

    // Returns productId -> quantity map for external service calls
    public Map<Long, Integer> getQuantityMap() {
        return items.entrySet().stream()
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getQuantity()));
    }

    public BigDecimal getSubtotal() {
        return items.values().stream()
            .map(CartItem::getLineTotal)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public BigDecimal getCartPromotionDiscount() {
        return cartPromotion.totalDiscount();
    }

    public BigDecimal getEffectiveTotal() {
        return getSubtotal()
                .subtract(cartPromotion.totalDiscount())
                .subtract(couponResult.totalDiscount())
                .max(BigDecimal.ZERO);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int getTotalItems() {
        return items.values().stream().mapToInt(CartItem::getQuantity).sum();
    }
}
