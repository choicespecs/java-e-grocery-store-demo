package com.demo.grocery.exception;

public class PaymentFailedException extends RuntimeException {

    private final String declineCode;

    public PaymentFailedException(String message, String declineCode) {
        super(message);
        this.declineCode = declineCode;
    }

    public String getDeclineCode() { return declineCode; }
}
