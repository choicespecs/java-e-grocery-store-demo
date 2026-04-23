package com.demo.grocery.repository;

import com.demo.grocery.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link com.demo.grocery.domain.Order}.
 *
 * <p>Provides standard CRUD operations inherited from {@link org.springframework.data.jpa.repository.JpaRepository}.
 * Used by {@link com.demo.grocery.service.OrderService} to persist and retrieve orders.
 */
public interface OrderRepository extends JpaRepository<Order, Long> {
}
