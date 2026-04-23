package com.demo.grocery.external.payment;

import java.math.BigDecimal;

public record PaymentResult(String paymentToken, BigDecimal amount, PaymentStatus status) {}
