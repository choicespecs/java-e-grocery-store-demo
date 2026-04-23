package com.demo.grocery.external.inventory;

import java.util.Map;

public record InventoryReservation(String reservationId, Map<Long, Integer> items) {}
