package com.ecommerce.shipping.listener;

import com.ecommerce.shipping.service.ShipmentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventListener {

    private final ObjectMapper objectMapper;
    private final ShipmentService shipmentService;

    @KafkaListener(topics = "${order.topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void onOrderEvent(String payload) {
        try {
            OrderEvent event = objectMapper.readValue(payload, OrderEvent.class);
            if ("ORDER_PLACED".equals(event.getEventType())) {
                shipmentService.createShipmentForOrder(event.getOrderId(), event.getCustomerId());
                log.info("Created shipment for order {}", event.getOrderId());
            }
        } catch (Exception ex) {
            log.error("Failed to process order event: {}", payload, ex);
        }
    }
}
