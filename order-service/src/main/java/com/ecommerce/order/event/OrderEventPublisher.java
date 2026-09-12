package com.ecommerce.order.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventPublisher {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Value("${order.topic}")
    private String topic;

    public void publish(OrderEvent event) {
        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(topic, String.valueOf(event.getOrderId()), payload);
            log.info("Published {} for order {}", event.getEventType(), event.getOrderId());
        } catch (Exception ex) {
            // An event-publish failure must never roll back or fail the order itself;
            // the order is already durably persisted. This is logged for visibility.
            log.error("Failed to publish order event for order {}: {}", event.getOrderId(), ex.getMessage());
        }
    }
}
