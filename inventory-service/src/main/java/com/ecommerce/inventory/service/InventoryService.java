package com.ecommerce.inventory.service;

import com.ecommerce.inventory.dto.InventoryResponse;
import com.ecommerce.inventory.dto.ItemQuantity;
import com.ecommerce.inventory.dto.StockRequest;
import com.ecommerce.inventory.exception.InsufficientStockException;
import com.ecommerce.inventory.exception.ResourceNotFoundException;
import com.ecommerce.inventory.model.InventoryItem;
import com.ecommerce.inventory.repository.InventoryItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Implements a simple reserve / confirm / release stock-hold pattern so that
 * order-service can perform a synchronous saga across inventory and payment
 * without ever selling stock that isn't really there:
 *
 *   reserve -> moves units from "available" to "reserved" (order pending payment)
 *   confirm -> removes reserved units permanently (payment succeeded)
 *   release -> moves reserved units back to "available" (payment failed / cancelled)
 */
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryItemRepository repository;

    @Transactional(readOnly = true)
    public List<InventoryResponse> findAll() {
        return repository.findAll().stream().map(InventoryResponse::from).toList();
    }

    @Transactional(readOnly = true)
    public InventoryResponse findByProductId(Long productId) {
        return InventoryResponse.from(getOrThrow(productId));
    }

    @Transactional
    public void reserve(StockRequest request) {
        for (ItemQuantity item : request.getItems()) {
            InventoryItem stock = repository.findWithLockByProductId(item.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("No inventory for product " + item.getProductId()));
            if (stock.getQuantityAvailable() < item.getQuantity()) {
                throw new InsufficientStockException(
                        "Insufficient stock for product " + item.getProductId()
                                + " (requested " + item.getQuantity() + ", available " + stock.getQuantityAvailable() + ")");
            }
            stock.setQuantityAvailable(stock.getQuantityAvailable() - item.getQuantity());
            stock.setQuantityReserved(stock.getQuantityReserved() + item.getQuantity());
            repository.save(stock);
        }
    }

    @Transactional
    public void confirm(StockRequest request) {
        for (ItemQuantity item : request.getItems()) {
            InventoryItem stock = repository.findWithLockByProductId(item.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("No inventory for product " + item.getProductId()));
            int newReserved = Math.max(0, stock.getQuantityReserved() - item.getQuantity());
            stock.setQuantityReserved(newReserved);
            repository.save(stock);
        }
    }

    @Transactional
    public void release(StockRequest request) {
        for (ItemQuantity item : request.getItems()) {
            InventoryItem stock = repository.findWithLockByProductId(item.getProductId())
                    .orElseThrow(() -> new ResourceNotFoundException("No inventory for product " + item.getProductId()));
            int qtyToRelease = Math.min(item.getQuantity(), stock.getQuantityReserved());
            stock.setQuantityReserved(stock.getQuantityReserved() - qtyToRelease);
            stock.setQuantityAvailable(stock.getQuantityAvailable() + qtyToRelease);
            repository.save(stock);
        }
    }

    private InventoryItem getOrThrow(Long productId) {
        return repository.findByProductId(productId)
                .orElseThrow(() -> new ResourceNotFoundException("No inventory for product " + productId));
    }
}
