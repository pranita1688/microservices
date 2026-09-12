package com.ecommerce.order.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Component
public class CartClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public CartClient(RestTemplate restTemplate, @Value("${services.cart-url}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    /** Best-effort: a successful order should never fail because the cart couldn't be cleared. */
    public void clearCart(Long customerId) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.set("X-User-Id", String.valueOf(customerId));
            restTemplate.exchange(baseUrl + "/api/cart", HttpMethod.DELETE, new HttpEntity<>(headers), Void.class);
        } catch (RestClientException ignored) {
            // Non-fatal; the cart will simply retain items the customer already checked out.
        }
    }
}
