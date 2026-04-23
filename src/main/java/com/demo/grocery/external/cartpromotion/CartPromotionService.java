package com.demo.grocery.external.cartpromotion;

import com.demo.grocery.dto.CartItem;

import java.util.Collection;
import java.util.Map;

/**
 * Automatic cart-promotion engine — no promo code required.
 *
 * <p>Called on every cart mutation (add, update, remove) and on {@code GET /cart} by
 * {@link com.demo.grocery.controller.CartController#refreshDiscounts}. Unlike
 * {@link com.demo.grocery.external.coupon.CouponService}, this service is never
 * fault-injected and always succeeds — it is treated as local business logic rather
 * than an external dependency.
 *
 * <p>Active deals are defined in
 * {@link com.demo.grocery.external.cartpromotion.stub.StubCartPromotionService#DEALS}.
 * The result is stored on the session-scoped {@link com.demo.grocery.dto.Cart} and
 * contributes the first tier of discount to the effective total.
 */
public interface CartPromotionService {

    CartPromotionResult evaluate(Collection<CartItem> items);

    /**
     * Returns productName → CartDealHint for every active automatic deal.
     * Callers use minQuantity to suppress the badge when stock is below the trigger threshold.
     */
    Map<String, CartDealHint> getAvailableDeals();
}
