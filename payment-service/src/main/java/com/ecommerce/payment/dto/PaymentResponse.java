package com.ecommerce.payment.dto;

import com.ecommerce.payment.model.Payment;
import com.ecommerce.payment.model.PaymentStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {
    private Long id;
    private Long orderId;
    private Long customerId;
    private BigDecimal amount;
    private String method;
    private PaymentStatus status;
    private String failureReason;

    public static PaymentResponse from(Payment p) {
        return new PaymentResponse(p.getId(), p.getOrderId(), p.getCustomerId(), p.getAmount(),
                p.getMethod(), p.getStatus(), p.getFailureReason());
    }
}
