package com.demo.grocery.external.payment;

import java.math.BigDecimal;

public record PaymentRequest(String cardNumber, String cardHolderName, BigDecimal amount, String idempotencyKey) {}
