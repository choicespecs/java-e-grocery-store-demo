package com.demo.grocery.external.coupon;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

public record CouponResult(List<CouponOffer> offers, BigDecimal totalDiscount) implements Serializable {

    public static CouponResult none() {
        return new CouponResult(List.of(), BigDecimal.ZERO);
    }

    public boolean hasOffers() {
        return !offers.isEmpty();
    }
}
