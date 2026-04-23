package com.demo.grocery.external.cartpromotion.stub;

import com.demo.grocery.dto.CartItem;
import com.demo.grocery.external.cartpromotion.CartDeal;
import com.demo.grocery.external.cartpromotion.CartDealHint;
import com.demo.grocery.external.cartpromotion.CartPromotionResult;
import com.demo.grocery.external.cartpromotion.CartPromotionService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simulates an external cart-promotion engine that applies automatic deals
 * based on item quantities — no promo code required.
 *
 * Active deals:
 *   Whole Milk (1 gal)     → Buy 2, Get 1 Free  (every 3rd unit is free)
 *   Organic Apples (1 lb)  → Buy 4, Save $2.00  (flat $2 off when qty ≥ 4)
 *   Bananas (bunch)        → Buy 3, Save $1.00  (flat $1 off when qty ≥ 3)
 */
@Service
public class StubCartPromotionService implements CartPromotionService {

    private enum DealType { BUY_X_GET_Y_FREE, QUANTITY_DOLLAR_OFF }

    private record DealDefinition(String productName, String description,
                                  DealType type, int threshold, double param) {

        BigDecimal calculate(CartItem item) {
            return switch (type) {
                case BUY_X_GET_Y_FREE -> {
                    int freeUnits = item.getQuantity() / threshold;
                    yield freeUnits > 0
                        ? item.getUnitPrice().multiply(BigDecimal.valueOf(freeUnits))
                              .setScale(2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
                }
                case QUANTITY_DOLLAR_OFF ->
                    item.getQuantity() >= threshold
                        ? BigDecimal.valueOf(param).setScale(2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO;
            };
        }
    }

    private static final List<DealDefinition> DEALS = List.of(
        new DealDefinition("Whole Milk (1 gal)",    "Buy 2, Get 1 Free",  DealType.BUY_X_GET_Y_FREE,   3, 0),
        new DealDefinition("Organic Apples (1 lb)", "Buy 4, Save $2.00",  DealType.QUANTITY_DOLLAR_OFF, 4, 2.00),
        new DealDefinition("Bananas (bunch)",        "Buy 3, Save $1.00",  DealType.QUANTITY_DOLLAR_OFF, 3, 1.00)
    );

    @Override
    public CartPromotionResult evaluate(Collection<CartItem> items) {
        List<CartDeal> deals = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;

        for (CartItem item : items) {
            for (DealDefinition deal : DEALS) {
                if (!item.getProductName().equals(deal.productName())) continue;
                BigDecimal discount = deal.calculate(item);
                if (discount.compareTo(BigDecimal.ZERO) > 0) {
                    deals.add(new CartDeal(item.getProductName(), deal.description(), discount));
                    total = total.add(discount);
                }
            }
        }

        return deals.isEmpty() ? CartPromotionResult.none() : new CartPromotionResult(deals, total);
    }

    @Override
    public Map<String, CartDealHint> getAvailableDeals() {
        Map<String, CartDealHint> result = new LinkedHashMap<>();
        for (DealDefinition d : DEALS) {
            result.put(d.productName(), new CartDealHint(d.description(), d.threshold()));
        }
        return result;
    }
}
