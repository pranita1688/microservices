package com.ecommerce.order.client;

import com.ecommerce.order.exception.PaymentServiceException;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;

@Component
public class PaymentClient {

    private final RestTemplate restTemplate;
    private final String baseUrl;

    public PaymentClient(RestTemplate restTemplate, @Value("${services.payment-url}") String baseUrl) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
    }

    public PaymentResult charge(Long orderId, Long customerId, BigDecimal amount, String method, String cardNumber) {
        try {
            ChargeRequest request = new ChargeRequest(orderId, customerId, amount, method, cardNumber);
            PaymentResult result = restTemplate.postForObject(baseUrl + "/api/payments/charge", request, PaymentResult.class);
            if (result == null) {
                throw new PaymentServiceException("Empty response from payment service");
            }
            return result;
        } catch (RestClientException ex) {
            throw new PaymentServiceException("Payment service unavailable: " + ex.getMessage());
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ChargeRequest {
        private Long orderId;
        private Long customerId;
        private BigDecimal amount;
        private String method;
        private String cardNumber;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PaymentResult {
        private Long id;
        private Long orderId;
        private String status; // SUCCESS | FAILED
        private String failureReason;

        public boolean isSuccess() {
            return "SUCCESS".equalsIgnoreCase(status);
        }
    }
}
