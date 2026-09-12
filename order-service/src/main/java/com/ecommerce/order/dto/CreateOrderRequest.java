package com.ecommerce.order.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class CreateOrderRequest {

    @NotNull
    private Long customerId;

    @NotEmpty
    @Valid
    private List<OrderItemRequest> items;

    /** Payment method: CARD, WALLET, COD. Defaults to CARD. */
    private String paymentMethod = "CARD";

    /**
     * Optional: pass 4000000000000002 to deliberately trigger a declined
     * payment and exercise the compensating-transaction path end to end.
     */
    private String cardNumber;
}
