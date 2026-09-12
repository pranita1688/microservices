package com.ecommerce.shipping.controller;

import com.ecommerce.shipping.model.Shipment;
import com.ecommerce.shipping.model.ShipmentStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ShipmentResponse {
    private Long orderId;
    private String trackingId;
    private ShipmentStatus status;

    public static ShipmentResponse from(Shipment s) {
        return new ShipmentResponse(s.getOrderId(), s.getTrackingId(), s.getStatus());
    }
}
