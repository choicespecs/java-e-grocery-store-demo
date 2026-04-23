package com.demo.grocery.external.payment;

import java.math.BigDecimal;

/**
 * Immutable response from {@link PaymentService#processPayment}.
 *
 * <p>The {@code paymentToken} is an opaque reference string (e.g. {@code "PAY-XXXXXXXX"})
 * assigned by the payment service. It is stored in {@link com.demo.grocery.domain.Order#paymentToken}
 * and is the only identifier used for subsequent refund calls — no raw card data is retained.
 */
public record PaymentResult(String paymentToken, BigDecimal amount, PaymentStatus status) {}
