package com.ecommerce.payment.controller;

import com.ecommerce.payment.dto.ChargeRequest;
import com.ecommerce.payment.dto.PaymentResponse;
import com.ecommerce.payment.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    @PostMapping("/charge")
    public PaymentResponse charge(@Valid @RequestBody ChargeRequest request) {
        return paymentService.charge(request);
    }

    @GetMapping("/{orderId}")
    public ResponseEntity<PaymentResponse> getByOrderId(@PathVariable Long orderId) {
        PaymentResponse response = paymentService.findByOrderId(orderId);
        return response == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(response);
    }
}
