package com.demo.grocery.exception;

/**
 * Thrown by {@link com.demo.grocery.external.promotion.stub.StubPromotionService#applyPromotion}
 * when the submitted promo code is not found in the known-codes table.
 *
 * <p>This is a Step 2 failure with no side effects. Caught separately in
 * {@link com.demo.grocery.controller.CheckoutController} to render an inline form
 * error next to the promo-code field rather than as a generic checkout error.
 */
public class InvalidPromotionException extends RuntimeException {

    public InvalidPromotionException(String message) {
        super(message);
    }
}
