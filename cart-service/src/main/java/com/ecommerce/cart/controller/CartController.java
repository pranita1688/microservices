package com.ecommerce.cart.controller;

import com.ecommerce.cart.dto.CartItemRequest;
import com.ecommerce.cart.dto.CartResponse;
import com.ecommerce.cart.service.CartService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * The caller's identity comes from the X-User-Id header, which api-gateway
 * injects after validating the customer's JWT. For direct/offline testing of
 * this service (bypassing the gateway) pass the header manually.
 */
@RestController
@RequestMapping("/api/cart")
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    @GetMapping
    public CartResponse getCart(@RequestHeader("X-User-Id") Long customerId) {
        return cartService.getCart(customerId);
    }

    @PostMapping("/items")
    public CartResponse addItem(@RequestHeader("X-User-Id") Long customerId,
                                 @Valid @RequestBody CartItemRequest request) {
        return cartService.addItem(customerId, request.getProductId(), request.getQuantity());
    }

    @PutMapping("/items/{productId}")
    public CartResponse setItemQuantity(@RequestHeader("X-User-Id") Long customerId,
                                         @PathVariable Long productId,
                                         @RequestParam int quantity) {
        return cartService.setItemQuantity(customerId, productId, quantity);
    }

    @DeleteMapping("/items/{productId}")
    public CartResponse removeItem(@RequestHeader("X-User-Id") Long customerId, @PathVariable Long productId) {
        return cartService.removeItem(customerId, productId);
    }

    @DeleteMapping
    public void clearCart(@RequestHeader("X-User-Id") Long customerId) {
        cartService.clearCart(customerId);
    }
}
