package com.ecommerce.analytics.listener;

import com.ecommerce.analytics.model.OrderEvent;
import com.ecommerce.analytics.service.AnalyticsService;
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
    private final AnalyticsService analyticsService;

    @KafkaListener(topics = "${order.topic}", groupId = "${spring.kafka.consumer.group-id}")
    public void onOrderEvent(String payload) {
        try {
            OrderEvent event = objectMapper.readValue(payload, OrderEvent.class);
            switch (event.getEventType()) {
                case "ORDER_PLACED" -> analyticsService.recordPlaced(event);
                case "ORDER_FAILED" -> analyticsService.recordFailed(event);
                default -> log.debug("Ignoring event type: {}", event.getEventType());
            }
        } catch (Exception ex) {
            log.error("Failed to process order event: {}", payload, ex);
        }
    }
}
