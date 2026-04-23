package com.demo.grocery.external.coupon;

import com.demo.grocery.dto.CartItem;

import java.util.Collection;
import java.util.List;

public interface CouponService {

    /**
     * Evaluates which coupon offers apply to the current cart and returns
     * the full set of applicable discounts.  Called on every cart mutation
     * (add / update / remove) so the displayed price is always up to date.
     * Subject to fault injection.
     */
    CouponResult evaluate(Collection<CartItem> items);

    /**
     * Returns static offer metadata — what offers exist and how to unlock them.
     * Never fault-injected: always callable even when the engine is DOWN,
     * so the UI can show "unavailable" rather than an empty list.
     */
    List<CouponOfferHint> getOfferCatalog();
}
