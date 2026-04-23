package com.demo.grocery.external.cartpromotion;

import java.math.BigDecimal;
import java.util.List;

/**
 * The result of evaluating automatic quantity-based cart deals.
 *
 * <p>Holds the list of {@link CartDeal} instances that are currently active (i.e. the
 * quantity threshold has been met) and the sum of their discounts as {@code totalDiscount}.
 * Use {@link #none()} when no deals apply.
 *
 * <p>Stored on the session-scoped {@link com.demo.grocery.dto.Cart} and accessed by
 * {@code Cart.getCartPromotionDiscount()} to compute the effective cart total.
 */
public record CartPromotionResult(List<CartDeal> deals, BigDecimal totalDiscount) {

    public static CartPromotionResult none() {
        return new CartPromotionResult(List.of(), BigDecimal.ZERO);
    }

    public boolean hasDeals() {
        return !deals.isEmpty();
    }
}
