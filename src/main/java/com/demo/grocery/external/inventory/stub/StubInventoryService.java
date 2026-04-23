package com.demo.grocery.external.inventory.stub;

import com.demo.grocery.demo.DemoFaultConfig;
import com.demo.grocery.demo.FaultInjector;
import com.demo.grocery.exception.InsufficientStockException;
import com.demo.grocery.external.inventory.InventoryReservation;
import com.demo.grocery.external.inventory.InventoryService;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulates an external warehouse inventory system.
 *
 * Stock lifecycle per checkout:
 *   available stock → (reserveStock) → reserved → (commitReservation) → gone
 *                                               → (releaseReservation) → back to available
 *
 * Fault injection is configured per saga step so you can observe exactly which
 * compensations fire depending on where in the sequence a failure occurs.
 * releaseReservation is intentionally NOT faulted — compensations must be reliable.
 */
@Service
public class StubInventoryService implements InventoryService {

    private final DemoFaultConfig faultConfig;

    // productId -> units currently available (not reserved)
    private final ConcurrentHashMap<Long, Integer> available = new ConcurrentHashMap<>();

    // reservationId -> (productId -> reserved quantity)
    private final ConcurrentHashMap<String, Map<Long, Integer>> reservations = new ConcurrentHashMap<>();

    public StubInventoryService(DemoFaultConfig faultConfig) {
        this.faultConfig = faultConfig;
    }

    /** Called by DataInitializer to seed stock levels on startup. */
    public void initializeStock(Long productId, int quantity) {
        available.put(productId, quantity);
    }

    @Override
    public void checkAvailability(Map<Long, Integer> requested) throws InsufficientStockException {
        FaultInjector.apply(faultConfig.getInventoryCheckMode(), "Inventory/checkAvailability",
            faultConfig.getSlowDelayMs(), faultConfig.getInventoryFlakyCounter());

        for (var entry : requested.entrySet()) {
            int inStock = available.getOrDefault(entry.getKey(), 0);
            if (inStock < entry.getValue()) {
                throw new InsufficientStockException(entry.getKey(), inStock, entry.getValue());
            }
        }
    }

    @Override
    public InventoryReservation reserveStock(Map<Long, Integer> requested) {
        FaultInjector.apply(faultConfig.getInventoryReserveMode(), "Inventory/reserveStock",
            faultConfig.getSlowDelayMs(), faultConfig.getInventoryFlakyCounter());

        String reservationId = UUID.randomUUID().toString();
        for (var entry : requested.entrySet()) {
            available.merge(entry.getKey(), -entry.getValue(), Integer::sum);
        }
        reservations.put(reservationId, new HashMap<>(requested));
        return new InventoryReservation(reservationId, new HashMap<>(requested));
    }

    @Override
    public void commitReservation(InventoryReservation reservation) {
        FaultInjector.apply(faultConfig.getInventoryCommitMode(), "Inventory/commitReservation",
            faultConfig.getSlowDelayMs(), faultConfig.getInventoryFlakyCounter());

        reservations.remove(reservation.reservationId());
    }

    @Override
    public void releaseReservation(InventoryReservation reservation) {
        // No fault injection — compensations must be reliable regardless of service health.
        Map<Long, Integer> held = reservations.remove(reservation.reservationId());
        if (held != null) {
            held.forEach((productId, qty) -> available.merge(productId, qty, Integer::sum));
        }
    }

    @Override
    public int getStock(Long productId) {
        return available.getOrDefault(productId, 0);
    }
}
