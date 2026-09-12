package com.ecommerce.shipping.listener;

import lombok.Data;

/** Local copy of the event contract published by order-service on the "order-events" topic. */
@Data
public class OrderEvent {
    private String eventType;
    private Long orderId;
    private Long customerId;
    private String status;
}
