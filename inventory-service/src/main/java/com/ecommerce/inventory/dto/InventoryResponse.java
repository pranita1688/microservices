package com.ecommerce.inventory.dto;

import com.ecommerce.inventory.model.InventoryItem;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class InventoryResponse {
    private Long productId;
    private int quantityAvailable;
    private int quantityReserved;

    public static InventoryResponse from(InventoryItem item) {
        return new InventoryResponse(item.getProductId(), item.getQuantityAvailable(), item.getQuantityReserved());
    }
}
