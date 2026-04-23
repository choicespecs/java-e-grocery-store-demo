package com.demo.grocery.external.coupon;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * A coupon offer that is currently applied to the cart.
 *
 * <p>Produced by {@link CouponService#evaluate} when a cart item combination meets
 * the conditions for an offer (e.g. BOGO, bundle, quantity-off, spend-threshold).
 *
 * <p>Fields:
 * <ul>
 *   <li>{@code offerId}     — unique identifier matching the {@link CouponOfferHint} catalog entry</li>
 *   <li>{@code title}       — short human-readable label shown in the cart UI</li>
 *   <li>{@code description} — detail shown when the offer is active</li>
 *   <li>{@code type}        — {@link OfferType} categorising the discount mechanism</li>
 *   <li>{@code discount}    — monetary saving to be subtracted from the cart total</li>
 * </ul>
 *
 * <p>Implements {@link java.io.Serializable} so it survives session serialisation alongside
 * the parent {@link CouponResult}.
 */
public record CouponOffer(
        String offerId,
        String title,
        String description,
        OfferType type,
        BigDecimal discount
) implements Serializable {}
