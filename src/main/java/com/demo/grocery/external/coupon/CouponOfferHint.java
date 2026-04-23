package com.demo.grocery.external.coupon;

import java.io.Serializable;

/** Static offer metadata returned by getOfferCatalog() — never fault-injected. */
public record CouponOfferHint(
        String offerId,
        String title,
        String hint
) implements Serializable {}
