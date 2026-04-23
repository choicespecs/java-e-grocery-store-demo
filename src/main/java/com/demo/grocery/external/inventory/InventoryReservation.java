package com.demo.grocery.external.inventory;

import java.util.Map;

/**
 * A token representing a held inventory reservation.
 *
 * <p>Returned by {@link InventoryService#reserveStock} at Step 3 of the checkout saga.
 * The {@code reservationId} is used to either commit (Step 6) or release (compensation)
 * the held stock. The {@code items} map is a snapshot of productId to reserved quantity
 * at the time of reservation.
 *
 * <p>Passed through {@code CheckoutService} to every downstream compensation call so that
 * {@code releaseReservation} knows exactly which units to return to the available pool.
 */
public record InventoryReservation(String reservationId, Map<Long, Integer> items) {}
