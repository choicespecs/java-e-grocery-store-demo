package com.demo.grocery.external.payment.stub;

import com.demo.grocery.demo.DemoFaultConfig;
import com.demo.grocery.demo.FaultInjector;
import com.demo.grocery.exception.PaymentFailedException;
import com.demo.grocery.external.payment.PaymentRequest;
import com.demo.grocery.external.payment.PaymentResult;
import com.demo.grocery.external.payment.PaymentService;
import com.demo.grocery.external.payment.PaymentStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulates a payment gateway (e.g. Stripe).
 *
 * Test card numbers:
 *   4111 1111 1111 1111  →  Success
 *   4000 0000 0000 0002  →  Generic decline  (triggers inventory rollback)
 *   4000 0000 0000 9995  →  Insufficient funds
 *   4000 0000 0000 0127  →  Incorrect CVC
 *
 * Idempotency: when enabled, a second request with the same idempotencyKey returns
 * the original result without re-charging — safe for network retries.  Disable the
 * toggle in the demo panel to see double-charging on duplicate submissions.
 */
@Service
public class StubPaymentService implements PaymentService {

    private static final Map<String, String> DECLINE_REASONS = Map.of(
        "4000000000000002", "do_not_honor",
        "4000000000009995", "insufficient_funds",
        "4000000000000127", "incorrect_cvc"
    );

    private final DemoFaultConfig faultConfig;

    // paymentToken -> amount; represents settled (charged) transactions
    private final ConcurrentHashMap<String, BigDecimal> settled = new ConcurrentHashMap<>();

    // idempotencyKey -> previously returned result; prevents double-charging on retry
    private final ConcurrentHashMap<String, PaymentResult> idempotentCache = new ConcurrentHashMap<>();

    public StubPaymentService(DemoFaultConfig faultConfig) {
        this.faultConfig = faultConfig;
    }

    @Override
    public PaymentResult processPayment(PaymentRequest request) throws PaymentFailedException {
        // Idempotency check — must happen BEFORE applying the fault so that a retry
        // after a transient failure (DOWN/FLAKY) still gets the original result once
        // the service recovers.
        String key = request.idempotencyKey();
        if (faultConfig.isPaymentIdempotencyEnabled() && key != null) {
            PaymentResult cached = idempotentCache.get(key);
            if (cached != null) {
                return cached; // safe replay — no second charge
            }
        }

        // Apply configured fault (throws ServiceUnavailableException for DOWN/FLAKY)
        FaultInjector.apply(faultConfig.getPaymentMode(), "Payment",
            faultConfig.getSlowDelayMs(), faultConfig.getPaymentFlakyCounter());

        // Card decline check
        String normalized = request.cardNumber().replaceAll("[\\s-]", "");
        String declineCode = DECLINE_REASONS.get(normalized);
        if (declineCode != null) {
            throw new PaymentFailedException(
                "Your card was declined (" + declineCode.replace('_', ' ') + ")",
                declineCode
            );
        }

        String token = "PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        PaymentResult result = new PaymentResult(token, request.amount(), PaymentStatus.SUCCESS);
        settled.put(token, request.amount());

        // Cache result for idempotent replay
        if (key != null) {
            idempotentCache.put(key, result);
        }

        return result;
    }

    @Override
    public void refund(String paymentToken) {
        settled.remove(paymentToken);
        // Invalidate the idempotent cache entry so a genuine re-attempt after refund
        // creates a fresh charge rather than returning the now-refunded token.
        idempotentCache.values().removeIf(r -> r.paymentToken().equals(paymentToken));
    }

    /** Exposed for demo diagnostics: number of settled (charged) transactions in memory. */
    public int getSettledCount() { return settled.size(); }
}
