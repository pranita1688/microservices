package com.ecommerce.inventory.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
public class StockRequest {

    @NotEmpty
    @Valid
    private List<ItemQuantity> items;
}
