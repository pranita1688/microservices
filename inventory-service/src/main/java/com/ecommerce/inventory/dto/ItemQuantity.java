package com.ecommerce.inventory.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ItemQuantity {

    @NotNull
    private Long productId;

    @Min(1)
    private int quantity;
}
