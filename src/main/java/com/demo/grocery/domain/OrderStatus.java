package com.demo.grocery.domain;

/**
 * Lifecycle states for a {@link Order}.
 *
 * <ul>
 *   <li>{@code PENDING}   — reserved for future use; not currently assigned by the saga.</li>
 *   <li>{@code CONFIRMED} — all six saga steps completed successfully; payment settled.</li>
 *   <li>{@code CANCELLED} — created by a compensating action at Step 6 (commit failure).
 *       The payment has been refunded and the inventory reservation released.</li>
 * </ul>
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    CANCELLED
}
