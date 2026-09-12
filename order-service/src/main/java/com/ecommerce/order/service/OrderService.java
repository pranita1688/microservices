package com.ecommerce.order.service;

import com.ecommerce.order.client.CartClient;
import com.ecommerce.order.client.InventoryClient;
import com.ecommerce.order.client.PaymentClient;
import com.ecommerce.order.client.ProductClient;
import com.ecommerce.order.dto.CreateOrderRequest;
import com.ecommerce.order.dto.OrderItemRequest;
import com.ecommerce.order.dto.OrderResponse;
import com.ecommerce.order.event.OrderEvent;
import com.ecommerce.order.event.OrderEventPublisher;
import com.ecommerce.order.exception.PaymentDeclinedException;
import com.ecommerce.order.exception.ResourceNotFoundException;
import com.ecommerce.order.exception.StockUnavailableException;
import com.ecommerce.order.model.Order;
import com.ecommerce.order.model.OrderItem;
import com.ecommerce.order.model.OrderStatus;
import com.ecommerce.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Orchestrates the checkout saga synchronously across three services:
 *
 *   1. price & validate items          (product-service)
 *   2. reserve stock                   (inventory-service)   -- compensatable
 *   3. charge payment                  (payment-service)
 *   4. on success: confirm stock, publish ORDER_PLACED, clear cart
 *      on decline: release stock (compensation), publish ORDER_FAILED
 *
 * This is "orchestration" rather than "choreography": order-service owns the
 * whole transaction and calls the other services directly and synchronously,
 * which keeps the happy/failure paths easy to follow for a learning-oriented
 * project. The Kafka event at the end is for services that only care about
 * the *outcome* (notifications, shipping, analytics), not for driving the
 * saga itself.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductClient productClient;
    private final InventoryClient inventoryClient;
    private final PaymentClient paymentClient;
    private final CartClient cartClient;
    private final OrderEventPublisher eventPublisher;

    @Transactional
    public OrderResponse placeOrder(CreateOrderRequest request) {
        Order order = buildPricedOrder(request);
        order = orderRepository.save(order); // PENDING, now has an id

        try {
            inventoryClient.reserve(request.getItems());
        } catch (StockUnavailableException ex) {
            failOrder(order, OrderStatus.STOCK_UNAVAILABLE, ex.getMessage());
            publishFailure(order, ex.getMessage());
            throw ex;
        }

        PaymentClient.PaymentResult payment = paymentClient.charge(
                order.getId(), order.getCustomerId(), order.getTotalAmount(),
                request.getPaymentMethod(), request.getCardNumber());

        if (!payment.isSuccess()) {
            inventoryClient.release(request.getItems());
            String reason = payment.getFailureReason() == null ? "Payment declined" : payment.getFailureReason();
            failOrder(order, OrderStatus.PAYMENT_FAILED, reason);
            publishFailure(order, reason);
            throw new PaymentDeclinedException(reason);
        }

        inventoryClient.confirm(request.getItems());
        order.setStatus(OrderStatus.PAID);
        order.setUpdatedAt(Instant.now());
        order = orderRepository.save(order);

        cartClient.clearCart(order.getCustomerId());
        publishPlaced(order);

        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public OrderResponse findById(Long id) {
        return OrderResponse.from(orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Order not found: " + id)));
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> findByCustomer(Long customerId) {
        return orderRepository.findByCustomerIdOrderByCreatedAtDesc(customerId).stream()
                .map(OrderResponse::from)
                .toList();
    }

    private Order buildPricedOrder(CreateOrderRequest request) {
        Order order = Order.builder()
                .customerId(request.getCustomerId())
                .status(OrderStatus.PENDING)
                .totalAmount(BigDecimal.ZERO)
                .build();

        BigDecimal total = BigDecimal.ZERO;
        for (OrderItemRequest itemRequest : request.getItems()) {
            ProductClient.ProductDto product = productClient.getProduct(itemRequest.getProductId());
            BigDecimal lineTotal = product.getPrice().multiply(BigDecimal.valueOf(itemRequest.getQuantity()));
            total = total.add(lineTotal);

            order.addItem(OrderItem.builder()
                    .productId(product.getId())
                    .productName(product.getName())
                    .quantity(itemRequest.getQuantity())
                    .unitPrice(product.getPrice())
                    .build());
        }
        order.setTotalAmount(total);
        return order;
    }

    private void failOrder(Order order, OrderStatus status, String reason) {
        order.setStatus(status);
        order.setFailureReason(reason);
        order.setUpdatedAt(Instant.now());
        orderRepository.save(order);
        log.warn("Order {} failed: {} ({})", order.getId(), reason, status);
    }

    private void publishPlaced(Order order) {
        eventPublisher.publish(new OrderEvent(
                "ORDER_PLACED", order.getId(), order.getCustomerId(),
                OrderResponse.from(order).getItems(), order.getTotalAmount(),
                order.getStatus().name(), null, Instant.now()));
    }

    private void publishFailure(Order order, String reason) {
        eventPublisher.publish(new OrderEvent(
                "ORDER_FAILED", order.getId(), order.getCustomerId(),
                OrderResponse.from(order).getItems(), order.getTotalAmount(),
                order.getStatus().name(), reason, Instant.now()));
    }
}
