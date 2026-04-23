package com.demo.grocery.external.promotion;

import java.math.BigDecimal;

public record PromotionResult(String code, String description, BigDecimal discount) {

    public static PromotionResult none() {
        return new PromotionResult(null, null, BigDecimal.ZERO);
    }

    public boolean isNone() {
        return code == null;
    }
}
