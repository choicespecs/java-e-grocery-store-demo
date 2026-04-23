package com.demo.grocery.dto;

import com.demo.grocery.domain.Product;
import com.demo.grocery.external.cartpromotion.CartPromotionResult;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

@Component
@SessionScope
public class Cart implements Serializable {

    private final Map<Long, CartItem> items = new LinkedHashMap<>();
    private CartPromotionResult cartPromotion = CartPromotionResult.none();

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

    public void clear() {
        items.clear();
        cartPromotion = CartPromotionResult.none();
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
        return getSubtotal().subtract(cartPromotion.totalDiscount()).max(BigDecimal.ZERO);
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public int getTotalItems() {
        return items.values().stream().mapToInt(CartItem::getQuantity).sum();
    }
}
