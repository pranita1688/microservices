package com.ecommerce.order.client;

import com.ecommerce.order.exception.ProductUnavailableException;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

@Component
public class ProductClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public ProductClient(RestTemplate restTemplate, @Value("${services.product-url}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    public ProductDto getProduct(Long productId) {
        try {
            ProductDto product = restTemplate.getForObject(baseUrl + "/api/products/" + productId, ProductDto.class);
            if (product == null) {
                throw new ProductUnavailableException("Product not found: " + productId);
            }
            return product;
        } catch (RestClientException ex) {
            throw new ProductUnavailableException("Could not look up product " + productId + ": " + ex.getMessage());
        }
    }

    @Data
    public static class ProductDto {
        private Long id;
        private String name;
        private BigDecimal price;
        private boolean active;
    }
}
