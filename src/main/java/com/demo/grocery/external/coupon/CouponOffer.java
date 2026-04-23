package com.demo.grocery.external.coupon;

import java.io.Serializable;
import java.math.BigDecimal;

public record CouponOffer(
        String offerId,
        String title,
        String description,
        OfferType type,
        BigDecimal discount
) implements Serializable {}
