package com.demo.grocery.external.inventory;

import com.demo.grocery.exception.InsufficientStockException;

import java.util.Map;

/**
 * External inventory/warehouse service contract.
 *
 * <p>The checkout saga calls these methods in the following sequence:
 * <ol>
 *   <li>{@link #checkAvailability} — read-only pre-flight; no stock held, no side effects.</li>
 *   <li>{@link #reserveStock} — holds stock so concurrent orders cannot oversell the same units.</li>
 *   <li>(Payment service charges the card — see PaymentService)</li>
 *   <li>{@link #commitReservation} — makes the hold permanent; reservation record is removed.</li>
 * </ol>
 *
 * <p>On any failure at or after Step 3, the compensating action is {@link #releaseReservation},
 * which returns the held units to the available pool. {@code releaseReservation} is never
 * fault-injected because compensating actions must succeed unconditionally.
 *
 * <p>The only {@code @Service} implementation is
 * {@link com.demo.grocery.external.inventory.stub.StubInventoryService}.
 * To substitute a real warehouse system, annotate the new implementation
 * {@code @Service @Profile("prod")} and the stub {@code @Profile("!prod")}.
 */
public interface InventoryService {

    /**
     * Checks whether all requested quantities are currently available.
     * Read-only — does not reserve or modify any stock.
     *
     * @throws InsufficientStockException if any product has insufficient available units
     * @throws com.demo.grocery.exception.ServiceUnavailableException if the service is DOWN or FLAKY
     */
    void checkAvailability(Map<Long, Integer> productQuantities) throws InsufficientStockException;

    /**
     * Reserves the requested quantities, reducing the available pool immediately.
     * Returns a reservation token used to commit or release the hold.
     *
     * @throws com.demo.grocery.exception.ServiceUnavailableException if the service is DOWN or FLAKY
     */
    InventoryReservation reserveStock(Map<Long, Integer> productQuantities);

    /**
     * Finalises a reservation, making the stock deduction permanent.
     * The reservation record is removed; the available pool is not restored.
     *
     * @throws com.demo.grocery.exception.ServiceUnavailableException if the service is DOWN or FLAKY
     */
    void commitReservation(InventoryReservation reservation);

    /**
     * Compensating action: releases a reservation, returning the held units to the available pool.
     * Never fault-injected — compensations must be reliable.
     */
    void releaseReservation(InventoryReservation reservation);

    /** Returns the number of units currently available (not reserved) for the given product. */
    int getStock(Long productId);
}
