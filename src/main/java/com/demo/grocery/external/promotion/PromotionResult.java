package com.demo.grocery.external.promotion;

import java.math.BigDecimal;

/**
 * The result of applying a promo code via {@link PromotionService#applyPromotion}.
 *
 * <p>Use {@link #none()} when no promo code was entered or when the promotion service
 * degraded gracefully. A "none" result has a zero discount and a null code, so the
 * order record will not store a promo code.
 *
 * <p>The {@code discount} is subtracted from the cart subtotal in
 * {@link com.demo.grocery.service.CheckoutService} <em>after</em> cart-promotion and
 * coupon discounts have already been applied — making it the third and final discount tier.
 */
public record PromotionResult(String code, String description, BigDecimal discount) {

    /** Returns a zero-discount result representing "no promo code applied". */
    public static PromotionResult none() {
        return new PromotionResult(null, null, BigDecimal.ZERO);
    }

    /** Returns true if this result represents no applied promo code. */
    public boolean isNone() {
        return code == null;
    }
}
