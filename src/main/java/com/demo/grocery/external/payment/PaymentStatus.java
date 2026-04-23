package com.demo.grocery.external.payment;

/**
 * Outcome states for a {@link PaymentResult}.
 *
 * <ul>
 *   <li>{@code SUCCESS}  — charge was accepted; {@code paymentToken} is valid for refund.</li>
 *   <li>{@code DECLINED} — card was rejected by the gateway; no funds moved.</li>
 *   <li>{@code REFUNDED} — charge was reversed via {@link PaymentService#refund}; not currently
 *       set by the stub (the token is simply removed from the settled map).</li>
 * </ul>
 */
public enum PaymentStatus {
    SUCCESS,
    DECLINED,
    REFUNDED
}
