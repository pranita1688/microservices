package com.ecommerce.notification.model;

import lombok.Data;

import java.math.BigDecimal;

/** Local copy of the event contract published by order-service on the "order-events" topic. */
@Data
public class OrderEvent {
    private String eventType;
    private Long orderId;
    private Long customerId;
    private BigDecimal totalAmount;
    private String status;
    private String reason;
}
