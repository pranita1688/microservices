package com.ecommerce.order.client;

import com.ecommerce.order.dto.OrderItemRequest;
import com.ecommerce.order.exception.StockUnavailableException;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

@Component
public class InventoryClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public InventoryClient(RestTemplate restTemplate, @Value("${services.inventory-url}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    public void reserve(List<OrderItemRequest> items) {
        try {
            restTemplate.postForEntity(baseUrl + "/api/inventory/reserve", new StockRequest(items), Void.class);
        } catch (HttpClientErrorException ex) {
            if (ex.getStatusCode() == HttpStatus.CONFLICT) {
                throw new StockUnavailableException(extractMessage(ex));
            }
            throw new StockUnavailableException("Inventory reservation failed: " + ex.getMessage());
        } catch (RestClientException ex) {
            throw new StockUnavailableException("Inventory service unavailable: " + ex.getMessage());
        }
    }

    public void confirm(List<OrderItemRequest> items) {
        restTemplate.postForEntity(baseUrl + "/api/inventory/confirm", new StockRequest(items), Void.class);
    }

    public void release(List<OrderItemRequest> items) {
        // Best-effort compensation: log-worthy but must never mask the original failure.
        try {
            restTemplate.postForEntity(baseUrl + "/api/inventory/release", new StockRequest(items), Void.class);
        } catch (RestClientException ignored) {
            // Compensation failures are swallowed here; in production this would be retried
            // via an outbox/dead-letter mechanism instead of a direct synchronous call.
        }
    }

    private String extractMessage(HttpClientErrorException ex) {
        String body = ex.getResponseBodyAsString();
        return (body == null || body.isBlank()) ? "Insufficient stock" : body;
    }

    @Data
    public static class StockRequest {
        private List<OrderItemRequest> items;

        public StockRequest(List<OrderItemRequest> items) {
            this.items = items;
        }
    }
}
