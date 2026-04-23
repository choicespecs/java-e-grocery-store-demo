package com.demo.grocery.exception;

/**
 * Thrown by {@link com.demo.grocery.service.CheckoutService} when the saga fails at a
 * step where side effects have already occurred (Steps 5 or 6) and all available
 * compensating actions have been attempted.
 *
 * <p>The wrapped {@code cause} is the original exception from the failing saga step.
 * {@link com.demo.grocery.controller.CheckoutController} catches this type and renders
 * a UI message confirming that compensations (refund, cancellation, stock release) ran.
 */
public class CheckoutException extends RuntimeException {

    public CheckoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
