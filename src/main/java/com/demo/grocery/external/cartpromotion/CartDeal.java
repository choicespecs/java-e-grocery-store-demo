package com.demo.grocery.external.cartpromotion;

import java.math.BigDecimal;

/**
 * An automatic quantity-based cart deal that has been applied to the current cart.
 *
 * <p>A {@code CartDeal} is produced by
 * {@link CartPromotionService#evaluate(java.util.Collection)} when a cart item meets the
 * quantity threshold for its associated deal (e.g. buying 3 or more Whole Milk units
 * triggers the "Buy 2, Get 1 Free" deal).
 *
 * <p>The {@code discount} field represents the monetary saving already earned — it is
 * subtracted from the cart total by {@link com.demo.grocery.dto.Cart#getEffectiveTotal()}.
 */
public record CartDeal(String productName, String description, BigDecimal discount) {}
