package com.demo.grocery.external.payment;

import com.demo.grocery.exception.PaymentFailedException;

public interface PaymentService {

    PaymentResult processPayment(PaymentRequest request) throws PaymentFailedException;

    void refund(String paymentToken);
}
