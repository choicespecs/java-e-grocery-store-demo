package com.demo.grocery.external.cartpromotion;

import java.math.BigDecimal;

public record CartDeal(String productName, String description, BigDecimal discount) {}
