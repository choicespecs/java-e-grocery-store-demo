package com.demo.grocery.external.cartpromotion;

import com.demo.grocery.dto.CartItem;

import java.util.Collection;
import java.util.Map;

public interface CartPromotionService {

    CartPromotionResult evaluate(Collection<CartItem> items);

    /**
     * Returns productName → CartDealHint for every active automatic deal.
     * Callers use minQuantity to suppress the badge when stock is below the trigger threshold.
     */
    Map<String, CartDealHint> getAvailableDeals();
}
