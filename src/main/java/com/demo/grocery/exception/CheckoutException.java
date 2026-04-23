package com.demo.grocery.exception;

public class CheckoutException extends RuntimeException {

    public CheckoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
