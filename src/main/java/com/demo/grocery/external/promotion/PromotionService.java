package com.demo.grocery.external.promotion;

import com.demo.grocery.exception.InvalidPromotionException;

import java.math.BigDecimal;

public interface PromotionService {

    PromotionResult applyPromotion(String code, BigDecimal subtotal) throws InvalidPromotionException;
}
