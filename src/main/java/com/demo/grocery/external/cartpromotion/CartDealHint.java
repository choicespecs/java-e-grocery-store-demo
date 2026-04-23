package com.demo.grocery.external.cartpromotion;

/** Describes an available automatic cart deal for display on the product listing. */
public record CartDealHint(String description, int minQuantity) {}
