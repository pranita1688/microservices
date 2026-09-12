package com.ecommerce.inventory.controller;

import com.ecommerce.inventory.dto.InventoryResponse;
import com.ecommerce.inventory.dto.StockRequest;
import com.ecommerce.inventory.service.InventoryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/inventory")
@RequiredArgsConstructor
public class InventoryController {

    private final InventoryService inventoryService;

    @GetMapping
    public List<InventoryResponse> list() {
        return inventoryService.findAll();
    }

    @GetMapping("/{productId}")
    public InventoryResponse get(@PathVariable Long productId) {
        return inventoryService.findByProductId(productId);
    }

    @PostMapping("/reserve")
    public void reserve(@Valid @RequestBody StockRequest request) {
        inventoryService.reserve(request);
    }

    @PostMapping("/confirm")
    public void confirm(@Valid @RequestBody StockRequest request) {
        inventoryService.confirm(request);
    }

    @PostMapping("/release")
    public void release(@Valid @RequestBody StockRequest request) {
        inventoryService.release(request);
    }
}
