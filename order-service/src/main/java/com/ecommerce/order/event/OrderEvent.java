package com.ecommerce.order.event;

import com.ecommerce.order.dto.OrderItemResponse;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Published to the "order-events" Kafka topic as plain JSON (not Avro/schema
 * registry, to keep the local setup simple). notification-service,
 * shipping-service, and analytics-service each consume the same message and
 * keep their own local copy of this shape - normal for loosely-coupled
 * microservices exchanging events by contract rather than by shared code.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderEvent {
    private String eventType; // ORDER_PLACED | ORDER_FAILED
    private Long orderId;
    private Long customerId;
    private List<OrderItemResponse> items;
    private BigDecimal totalAmount;
    private String status;
    private String reason;
    private Instant timestamp;
}
