package com.ecommerce.cart.service;

import com.ecommerce.cart.dto.CartItemResponse;
import com.ecommerce.cart.dto.CartResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The cart is stored as a Redis hash keyed by "cart:{customerId}" where each
 * field is a productId and each value is the quantity. Redis gives us cheap
 * per-user carts with a TTL for automatic abandoned-cart cleanup, without
 * needing a relational schema for something this ephemeral.
 */
@Service
@RequiredArgsConstructor
public class CartService {

    private final StringRedisTemplate redisTemplate;

    @Value("${cart.ttl-hours:72}")
    private long ttlHours;

    private String key(Long customerId) {
        return "cart:" + customerId;
    }

    public CartResponse getCart(Long customerId) {
        HashOperations<String, String, String> ops = redisTemplate.opsForHash();
        Map<String, String> entries = ops.entries(key(customerId));
        List<CartItemResponse> items = entries.entrySet().stream()
                .map(e -> new CartItemResponse(Long.valueOf(e.getKey()), Integer.parseInt(e.getValue())))
                .toList();
        return new CartResponse(customerId, items);
    }

    public CartResponse addItem(Long customerId, Long productId, int quantity) {
        String key = key(customerId);
        HashOperations<String, String, String> ops = redisTemplate.opsForHash();
        ops.increment(key, String.valueOf(productId), quantity);
        redisTemplate.expire(key, Duration.ofHours(ttlHours).toMillis(), TimeUnit.MILLISECONDS);
        return getCart(customerId);
    }

    public CartResponse setItemQuantity(Long customerId, Long productId, int quantity) {
        String key = key(customerId);
        HashOperations<String, String, String> ops = redisTemplate.opsForHash();
        if (quantity <= 0) {
            ops.delete(key, String.valueOf(productId));
        } else {
            ops.put(key, String.valueOf(productId), String.valueOf(quantity));
            redisTemplate.expire(key, Duration.ofHours(ttlHours).toMillis(), TimeUnit.MILLISECONDS);
        }
        return getCart(customerId);
    }

    public CartResponse removeItem(Long customerId, Long productId) {
        redisTemplate.opsForHash().delete(key(customerId), String.valueOf(productId));
        return getCart(customerId);
    }

    public void clearCart(Long customerId) {
        redisTemplate.delete(key(customerId));
    }
}
