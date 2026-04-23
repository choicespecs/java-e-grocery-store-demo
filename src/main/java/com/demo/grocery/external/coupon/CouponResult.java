package com.demo.grocery.external.coupon;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * The result of evaluating coupon-engine offers against the current cart.
 *
 * <p>Holds the list of {@link CouponOffer} instances that are currently applicable and
 * the sum of their discounts as {@code totalDiscount}. Use {@link #none()} when the
 * engine is unavailable or when no offers qualify.
 *
 * <p>Stored on the session-scoped {@link com.demo.grocery.dto.Cart} and accessed by
 * {@code Cart.getCouponDiscount()} to compute the effective cart total. Implements
 * {@link java.io.Serializable} so it survives session serialisation.
 */
public record CouponResult(List<CouponOffer> offers, BigDecimal totalDiscount) implements Serializable {

    public static CouponResult none() {
        return new CouponResult(List.of(), BigDecimal.ZERO);
    }

    public boolean hasOffers() {
        return !offers.isEmpty();
    }
}
