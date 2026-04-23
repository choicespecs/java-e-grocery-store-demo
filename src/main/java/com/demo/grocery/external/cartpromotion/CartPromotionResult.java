package com.demo.grocery.external.cartpromotion;

import java.math.BigDecimal;
import java.util.List;

public record CartPromotionResult(List<CartDeal> deals, BigDecimal totalDiscount) {

    public static CartPromotionResult none() {
        return new CartPromotionResult(List.of(), BigDecimal.ZERO);
    }

    public boolean hasDeals() {
        return !deals.isEmpty();
    }
}
