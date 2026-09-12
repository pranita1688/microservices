package com.ecommerce.shipping.service;

import com.ecommerce.shipping.model.Shipment;
import com.ecommerce.shipping.model.ShipmentStatus;
import com.ecommerce.shipping.repository.ShipmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ShipmentService {

    private final ShipmentRepository repository;

    @Transactional
    public void createShipmentForOrder(Long orderId, Long customerId) {
        if (repository.findByOrderId(orderId).isPresent()) {
            return; // idempotent: Kafka delivery is at-least-once
        }
        Shipment shipment = Shipment.builder()
                .orderId(orderId)
                .customerId(customerId)
                .trackingId("TRK-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase())
                .status(ShipmentStatus.PROCESSING)
                .build();
        repository.save(shipment);
    }

    @Transactional(readOnly = true)
    public Optional<Shipment> findByOrderId(Long orderId) {
        return repository.findByOrderId(orderId);
    }
}
