package com.ecommerce.payment.service;

import com.ecommerce.payment.dto.ChargeRequest;
import com.ecommerce.payment.dto.PaymentResponse;
import com.ecommerce.payment.model.Payment;
import com.ecommerce.payment.model.PaymentStatus;
import com.ecommerce.payment.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Simulates a payment gateway. No real money moves: this exists so the
 * order saga (reserve stock -> charge -> confirm/release) can be exercised
 * end to end, including its failure path, without any external dependency.
 *
 * Well-known test card numbers (same convention as Stripe) let callers force
 * a decline on demand:
 *   4000000000000002 -> always declined
 *   anything else     -> always approved
 */
@Service
@RequiredArgsConstructor
public class PaymentService {

    private static final String DECLINED_TEST_CARD = "4000000000000002";

    private final PaymentRepository repository;

    @Transactional
    public PaymentResponse charge(ChargeRequest request) {
        boolean declined = DECLINED_TEST_CARD.equals(request.getCardNumber());

        Payment payment = Payment.builder()
                .orderId(request.getOrderId())
                .customerId(request.getCustomerId())
                .amount(request.getAmount())
                .method(request.getMethod() == null ? "CARD" : request.getMethod())
                .status(declined ? PaymentStatus.FAILED : PaymentStatus.SUCCESS)
                .failureReason(declined ? "Card declined by issuer" : null)
                .build();

        return PaymentResponse.from(repository.save(payment));
    }

    @Transactional(readOnly = true)
    public PaymentResponse findByOrderId(Long orderId) {
        return repository.findByOrderId(orderId)
                .map(PaymentResponse::from)
                .orElse(null);
    }
}
