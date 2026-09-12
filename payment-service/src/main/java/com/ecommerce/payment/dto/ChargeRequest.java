package com.ecommerce.payment.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class ChargeRequest {

    @NotNull
    private Long orderId;

    @NotNull
    private Long customerId;

    @NotNull
    @DecimalMin(value = "0.0", inclusive = false)
    private BigDecimal amount;

    /** Defaults to a generic card charge; "CARD", "WALLET", "COD" are all accepted. */
    private String method = "CARD";

    /**
     * Optional test card number to deliberately exercise the failure path end to end.
     * Using the well-known Stripe test decline number makes failures reproducible on demand.
     */
    private String cardNumber;
}
