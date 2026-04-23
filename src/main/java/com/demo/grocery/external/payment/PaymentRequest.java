package com.demo.grocery.external.payment;

import java.math.BigDecimal;

/**
 * Immutable request object passed to {@link PaymentService#processPayment}.
 *
 * <p>Constructed from the session cart's computed {@code finalTotal} and the card details
 * captured in {@link com.demo.grocery.dto.CheckoutRequest}. The {@code idempotencyKey}
 * (a UUID generated once per form load) enables the payment service to deduplicate
 * retries without double-charging.
 *
 * <p>Card data ({@code cardNumber}, {@code cardHolderName}) exists in memory only during
 * the payment call and is never written to the database or logs.
 */
public record PaymentRequest(String cardNumber, String cardHolderName, BigDecimal amount, String idempotencyKey) {}
