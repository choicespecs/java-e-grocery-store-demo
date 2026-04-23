package com.demo.grocery.external.promotion;

import com.demo.grocery.exception.InvalidPromotionException;

import java.math.BigDecimal;

/**
 * External promotions/offers service contract — promo-code validation and discount calculation.
 *
 * <p>Called at Step 2 of the checkout saga ({@code applyPromotion}). This is a read-only,
 * non-critical-path dependency. If the service is unavailable, {@code CheckoutService}
 * decides whether to abort or continue without a discount based on the
 * {@code promotionGracefulDegradation} toggle in
 * {@link com.demo.grocery.demo.DemoFaultConfig}.
 *
 * <p>The only {@code @Service} implementation is
 * {@link com.demo.grocery.external.promotion.stub.StubPromotionService}.
 * Swap for a real promotions engine by annotating the new class
 * {@code @Service @Profile("prod")} and the stub {@code @Profile("!prod")}.
 */
public interface PromotionService {

    PromotionResult applyPromotion(String code, BigDecimal subtotal) throws InvalidPromotionException;
}
