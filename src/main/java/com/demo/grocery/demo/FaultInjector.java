package com.demo.grocery.demo;

import com.demo.grocery.exception.ServiceUnavailableException;

import java.util.concurrent.atomic.AtomicInteger;

public final class FaultInjector {

    private FaultInjector() {}

    /**
     * Applies the configured fault mode for the named service operation.
     * DOWN and FLAKY throw ServiceUnavailableException; SLOW blocks the caller thread.
     * Note: SLOW ties up the request thread — real systems use async timeouts and
     * circuit breakers (e.g. Resilience4j) to avoid thread exhaustion.
     */
    public static void apply(FaultMode mode, String label, int slowMs, AtomicInteger flakyCounter) {
        switch (mode) {
            case DOWN -> throw new ServiceUnavailableException(
                label + " service is currently unavailable (simulated outage)");

            case SLOW -> {
                try {
                    Thread.sleep(slowMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            case FLAKY -> {
                int call = flakyCounter.incrementAndGet();
                if (call % 2 == 0) {
                    throw new ServiceUnavailableException(
                        label + " service returned an intermittent error (call #" + call + " failed)");
                }
            }

            case NORMAL -> { /* no-op */ }
        }
    }
}
