package com.demo.grocery.exception;

/**
 * Thrown by {@link com.demo.grocery.external.payment.stub.StubPaymentService#processPayment}
 * when the card is declined by the payment gateway.
 *
 * <p>This is a Step 4 failure with one side effect already recorded: the inventory reservation
 * at Step 3. The compensating action is {@code releaseReservation}, which is performed in
 * {@code CheckoutService} before this exception propagates to the controller.
 *
 * <p>The {@code declineCode} (e.g. {@code "insufficient_funds"}, {@code "do_not_honor"}) is
 * displayed to the user as part of the payment-failed error message.
 */
public class PaymentFailedException extends RuntimeException {

    private final String declineCode;

    public PaymentFailedException(String message, String declineCode) {
        super(message);
        this.declineCode = declineCode;
    }

    public String getDeclineCode() { return declineCode; }
}
