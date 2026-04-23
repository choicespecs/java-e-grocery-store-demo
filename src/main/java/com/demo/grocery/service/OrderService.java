package com.demo.grocery.service;

import com.demo.grocery.domain.Order;
import com.demo.grocery.domain.OrderItem;
import com.demo.grocery.domain.OrderStatus;
import com.demo.grocery.dto.Cart;
import com.demo.grocery.dto.CartItem;
import com.demo.grocery.external.payment.PaymentResult;
import com.demo.grocery.external.promotion.PromotionResult;
import com.demo.grocery.repository.OrderRepository;
import com.demo.grocery.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Optional;

@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;

    public OrderService(OrderRepository orderRepository, ProductRepository productRepository) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public Order createOrder(Cart cart, PromotionResult promotion, PaymentResult payment, BigDecimal total) {
        Order order = new Order();
        order.setStatus(OrderStatus.CONFIRMED);
        order.setSubtotal(cart.getSubtotal());
        order.setDiscount(promotion.discount());
        order.setTotal(total);
        order.setPromoCode(promotion.isNone() ? null : promotion.code());
        order.setPaymentToken(payment.paymentToken());

        for (CartItem cartItem : cart.getItems()) {
            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(order);
            orderItem.setProductId(cartItem.getProductId());
            orderItem.setProductName(cartItem.getProductName());
            orderItem.setUnitPrice(cartItem.getUnitPrice());
            orderItem.setQuantity(cartItem.getQuantity());
            order.getItems().add(orderItem);

            // Sync the locally-cached stock in the DB so the product listing stays accurate
            productRepository.decrementStock(cartItem.getProductId(), cartItem.getQuantity());
        }

        return orderRepository.save(order);
    }

    @Transactional
    public void cancelOrder(Order order) {
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);
    }

    public Optional<Order> findById(Long id) {
        return orderRepository.findById(id);
    }
}
