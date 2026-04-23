package com.demo.grocery.external.inventory;

import com.demo.grocery.exception.InsufficientStockException;

import java.util.Map;

/**
 * External inventory/warehouse service contract.
 *
 * Checkout saga sequence:
 *   1. checkAvailability  — read-only pre-flight, no side effects
 *   2. reserveStock       — holds stock so concurrent orders can't oversell
 *   3. processPayment     — (PaymentService) charge the customer
 *      └─ on failure → releaseReservation (compensation)
 *   4. commitReservation  — finalize the deduction; reservation is now permanent
 */
public interface InventoryService {

    void checkAvailability(Map<Long, Integer> productQuantities) throws InsufficientStockException;

    InventoryReservation reserveStock(Map<Long, Integer> productQuantities);

    void commitReservation(InventoryReservation reservation);

    void releaseReservation(InventoryReservation reservation);

    int getStock(Long productId);
}
