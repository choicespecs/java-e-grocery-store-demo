package com.demo.grocery.external.payment;

import com.demo.grocery.exception.PaymentFailedException;

/**
 * External payment gateway service contract.
 *
 * <p>In the checkout saga, this is Step 4 — the first call with financial side effects.
 * It runs on a dedicated daemon thread in {@link com.demo.grocery.service.CheckoutService}
 * with a configurable timeout ({@code paymentTimeoutMs} from
 * {@link com.demo.grocery.demo.DemoFaultConfig}). On any failure, the compensating action
 * is {@link #refund} followed by inventory release.
 *
 * <p><strong>Idempotency:</strong> each call carries an {@code idempotencyKey} (a UUID
 * generated once per checkout form load). When idempotency is enabled, a second call with
 * the same key returns the cached result rather than issuing a second charge — safe for
 * retries and accidental double-submits.
 *
 * <p>The only {@code @Service} implementation is
 * {@link com.demo.grocery.external.payment.stub.StubPaymentService}.
 * Swap for a real gateway (Stripe, Adyen) by annotating the new class
 * {@code @Service @Profile("prod")} and the stub {@code @Profile("!prod")}.
 */
public interface PaymentService {

    /**
     * Charges the customer's card for the given amount.
     *
     * @param request card details, amount, and idempotency key
     * @return a {@link PaymentResult} containing an opaque payment token
     * @throws PaymentFailedException if the card is declined (not a transient error)
     * @throws com.demo.grocery.exception.ServiceUnavailableException if the service is DOWN or FLAKY
     */
    PaymentResult processPayment(PaymentRequest request) throws PaymentFailedException;

    /**
     * Refunds a previously settled charge identified by its payment token.
     * This is the compensating action for Steps 5 and 6 of the checkout saga.
     * After a successful refund, the token is removed from the settled map and
     * invalidated in the idempotency cache so a genuine retry creates a fresh charge.
     */
    void refund(String paymentToken);
}
