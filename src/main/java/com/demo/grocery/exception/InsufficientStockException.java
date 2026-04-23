package com.demo.grocery.exception;

/**
 * Thrown by {@link com.demo.grocery.external.inventory.stub.StubInventoryService#checkAvailability}
 * when the requested quantity for a product exceeds what is currently available.
 *
 * <p>This is a Step 1 failure — no stock has been reserved and no side effects have occurred,
 * so it is safe to retry. Caught by {@link com.demo.grocery.controller.CheckoutController}
 * which renders an inline "items no longer available" message.
 *
 * <p>Carries the {@code productId}, {@code available} count, and {@code requested} count
 * for diagnostic use.
 */
public class InsufficientStockException extends RuntimeException {

    private final Long productId;
    private final int available;
    private final int requested;

    public InsufficientStockException(Long productId, int available, int requested) {
        super(String.format("Product %d has only %d units available, but %d were requested",
            productId, available, requested));
        this.productId = productId;
        this.available = available;
        this.requested = requested;
    }

    public Long getProductId() { return productId; }
    public int getAvailable() { return available; }
    public int getRequested() { return requested; }
}
