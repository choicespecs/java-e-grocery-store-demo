package com.demo.grocery.demo;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Singleton that holds the current fault-injection settings for every stub service.
 * The demo control panel reads and writes this bean; stubs read it on each call.
 *
 * Volatile fields keep reads/writes visible across threads without needing
 * synchronized blocks — acceptable for a demo toggle; not a substitute for
 * proper distributed config in production.
 */
@Component
public class DemoFaultConfig {

    // --- Inventory (separate fault per saga step) ---
    private volatile FaultMode inventoryCheckMode   = FaultMode.NORMAL;
    private volatile FaultMode inventoryReserveMode = FaultMode.NORMAL;
    private volatile FaultMode inventoryCommitMode  = FaultMode.NORMAL;
    private final AtomicInteger inventoryFlakyCounter = new AtomicInteger(0);

    // --- Payment ---
    private volatile FaultMode paymentMode = FaultMode.NORMAL;
    private volatile boolean   paymentIdempotencyEnabled = true;
    private final AtomicInteger paymentFlakyCounter = new AtomicInteger(0);

    // --- Promotion ---
    private volatile FaultMode promotionMode = FaultMode.NORMAL;
    /**
     * When true and the promotion service is unavailable, CheckoutService skips
     * the discount and continues — graceful degradation of a non-critical dependency.
     * When false, the unavailability is treated as a hard failure and checkout aborts.
     */
    private volatile boolean promotionGracefulDegradation = true;
    private final AtomicInteger promotionFlakyCounter = new AtomicInteger(0);

    // --- Shared ---
    private volatile int slowDelayMs = 5000;
    /**
     * Maximum ms CheckoutService will wait for the payment service before timing out
     * and triggering the inventory compensation. Set slowDelayMs > paymentTimeoutMs
     * and SLOW mode to observe a live timeout rollback.
     */
    private volatile int paymentTimeoutMs = 3000;

    // ---- Reset ---------------------------------------------------------------

    public void resetAll() {
        inventoryCheckMode   = FaultMode.NORMAL;
        inventoryReserveMode = FaultMode.NORMAL;
        inventoryCommitMode  = FaultMode.NORMAL;
        paymentMode          = FaultMode.NORMAL;
        promotionMode        = FaultMode.NORMAL;
        promotionGracefulDegradation = true;
        paymentIdempotencyEnabled    = true;
        slowDelayMs = 5000;
        paymentTimeoutMs = 3000;
        inventoryFlakyCounter.set(0);
        paymentFlakyCounter.set(0);
        promotionFlakyCounter.set(0);
    }

    public boolean isAllNormal() {
        return inventoryCheckMode   == FaultMode.NORMAL
            && inventoryReserveMode == FaultMode.NORMAL
            && inventoryCommitMode  == FaultMode.NORMAL
            && paymentMode          == FaultMode.NORMAL
            && promotionMode        == FaultMode.NORMAL;
    }

    // ---- Getters / Setters ---------------------------------------------------

    public FaultMode getInventoryCheckMode()   { return inventoryCheckMode; }
    public void setInventoryCheckMode(FaultMode m)   { inventoryCheckMode = m; }

    public FaultMode getInventoryReserveMode() { return inventoryReserveMode; }
    public void setInventoryReserveMode(FaultMode m) { inventoryReserveMode = m; }

    public FaultMode getInventoryCommitMode()  { return inventoryCommitMode; }
    public void setInventoryCommitMode(FaultMode m)  { inventoryCommitMode = m; }

    public AtomicInteger getInventoryFlakyCounter() { return inventoryFlakyCounter; }

    public FaultMode getPaymentMode() { return paymentMode; }
    public void setPaymentMode(FaultMode m) { paymentMode = m; }

    public boolean isPaymentIdempotencyEnabled() { return paymentIdempotencyEnabled; }
    public void setPaymentIdempotencyEnabled(boolean b) { paymentIdempotencyEnabled = b; }

    public AtomicInteger getPaymentFlakyCounter() { return paymentFlakyCounter; }

    public FaultMode getPromotionMode() { return promotionMode; }
    public void setPromotionMode(FaultMode m) { promotionMode = m; }

    public boolean isPromotionGracefulDegradation() { return promotionGracefulDegradation; }
    public void setPromotionGracefulDegradation(boolean b) { promotionGracefulDegradation = b; }

    public AtomicInteger getPromotionFlakyCounter() { return promotionFlakyCounter; }

    public int getSlowDelayMs() { return slowDelayMs; }
    public void setSlowDelayMs(int ms) { slowDelayMs = ms; }

    public int getPaymentTimeoutMs() { return paymentTimeoutMs; }
    public void setPaymentTimeoutMs(int ms) { paymentTimeoutMs = ms; }
}
