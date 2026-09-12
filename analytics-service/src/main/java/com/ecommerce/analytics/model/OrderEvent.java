package com.ecommerce.analytics.model;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** Local copy of the event contract published by order-service on the "order-events" topic. */
@Data
public class OrderEvent {
    private String eventType;
    private Long orderId;
    private Long customerId;
    private List<Item> items;
    private BigDecimal totalAmount;
    private String status;

    @Data
    public static class Item {
        private Long productId;
        private String productName;
        private int quantity;
    }
}
