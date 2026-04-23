package com.demo.grocery.external.promotion.stub;

import com.demo.grocery.demo.DemoFaultConfig;
import com.demo.grocery.demo.FaultInjector;
import com.demo.grocery.exception.InvalidPromotionException;
import com.demo.grocery.external.promotion.PromotionResult;
import com.demo.grocery.external.promotion.PromotionService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

/**
 * Simulates an external promotions/offers service.
 *
 * Valid codes for testing:
 *   SAVE10   → 10% off subtotal
 *   SAVE20   → 20% off subtotal
 *   FLAT5    → $5.00 off subtotal
 *   WELCOME  → 15% off subtotal
 *
 * When the fault mode is DOWN or FLAKY, CheckoutService decides whether to treat
 * the failure as a hard stop (promotionGracefulDegradation = false) or silently
 * skip the discount and continue (promotionGracefulDegradation = true).  This
 * toggle demonstrates a key design question: is this dependency critical-path?
 */
@Service
public class StubPromotionService implements PromotionService {

    private enum DiscountType { PERCENTAGE, FIXED }

    private record PromoDefinition(String description, double rate, DiscountType type) {
        BigDecimal calculateDiscount(BigDecimal subtotal) {
            return switch (type) {
                case PERCENTAGE -> subtotal.multiply(BigDecimal.valueOf(rate))
                    .setScale(2, RoundingMode.HALF_UP);
                case FIXED -> BigDecimal.valueOf(rate).min(subtotal);
            };
        }
    }

    private static final Map<String, PromoDefinition> PROMOS = Map.of(
        "SAVE10",  new PromoDefinition("10% off your order", 0.10, DiscountType.PERCENTAGE),
        "SAVE20",  new PromoDefinition("20% off your order", 0.20, DiscountType.PERCENTAGE),
        "FLAT5",   new PromoDefinition("$5.00 off your order", 5.00, DiscountType.FIXED),
        "WELCOME", new PromoDefinition("15% off for new customers", 0.15, DiscountType.PERCENTAGE),
        "FRESH",   new PromoDefinition("$4.00 off fresh picks", 4.00, DiscountType.FIXED),
        "BIG25",   new PromoDefinition("25% off your order", 0.25, DiscountType.PERCENTAGE),
        "HALFOFF", new PromoDefinition("50% off your order", 0.50, DiscountType.PERCENTAGE)
    );

    private final DemoFaultConfig faultConfig;

    public StubPromotionService(DemoFaultConfig faultConfig) {
        this.faultConfig = faultConfig;
    }

    @Override
    public PromotionResult applyPromotion(String code, BigDecimal subtotal) throws InvalidPromotionException {
        FaultInjector.apply(faultConfig.getPromotionMode(), "Promotion",
            faultConfig.getSlowDelayMs(), faultConfig.getPromotionFlakyCounter());

        PromoDefinition promo = PROMOS.get(code.toUpperCase().trim());
        if (promo == null) {
            throw new InvalidPromotionException("Promo code \"" + code + "\" is not valid or has expired.");
        }
        BigDecimal discount = promo.calculateDiscount(subtotal);
        return new PromotionResult(code.toUpperCase(), promo.description(), discount);
    }
}
