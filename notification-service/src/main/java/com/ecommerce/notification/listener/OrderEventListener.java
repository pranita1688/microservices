package com.ecommerce.notification.listener;

import com.ecommerce.notification.model.OrderEvent;
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

    @KafkaListener(topics = "${order.topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void onOrderEvent(String payload) {
        try {
            OrderEvent event = objectMapper.readValue(payload, OrderEvent.class);
            switch (event.getEventType()) {
                case "ORDER_PLACED" -> sendOrderConfirmation(event);
                case "ORDER_FAILED" -> sendOrderFailure(event);
                default -> log.info("Ignoring unknown event type: {}", event.getEventType());
            }
        } catch (Exception ex) {
            log.error("Failed to process order event: {}", payload, ex);
        }
    }

    private void sendOrderConfirmation(OrderEvent event) {
        log.info("[EMAIL] To customer #{}: Your order #{} for {} has been confirmed and paid. Thank you!",
                event.getCustomerId(), event.getOrderId(), event.getTotalAmount());
    }

    private void sendOrderFailure(OrderEvent event) {
        log.info("[EMAIL] To customer #{}: Unfortunately order #{} could not be completed ({}).",
                event.getCustomerId(), event.getOrderId(), event.getReason());
    }
}
