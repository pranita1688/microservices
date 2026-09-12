package com.ecommerce.analytics.service;

import com.ecommerce.analytics.model.OrderEvent;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Deliberately simple in-memory rollup: this is a demo of "analytics
 * consumes the same event stream as everyone else", not a real OLAP engine.
 * Metrics reset on restart; see the note in application.yml.
 */
@Service
public class AnalyticsService {

    private final AtomicLong ordersPlaced = new AtomicLong();
    private final AtomicLong ordersFailed = new AtomicLong();
    private volatile BigDecimal totalRevenue = BigDecimal.ZERO;
    private final Object revenueLock = new Object();
    private final Map<Long, LongAdder> unitsSoldByProduct = new ConcurrentHashMap<>();

    public void recordPlaced(OrderEvent event) {
        ordersPlaced.incrementAndGet();
        synchronized (revenueLock) {
            totalRevenue = totalRevenue.add(event.getTotalAmount());
        }
        if (event.getItems() != null) {
            for (OrderEvent.Item item : event.getItems()) {
                unitsSoldByProduct.computeIfAbsent(item.getProductId(), id -> new LongAdder())
                        .add(item.getQuantity());
            }
        }
    }

    public void recordFailed(OrderEvent event) {
        ordersFailed.incrementAndGet();
    }

    public Summary summary() {
        List<ProductSales> topProducts = unitsSoldByProduct.entrySet().stream()
                .map(e -> new ProductSales(e.getKey(), e.getValue().sum()))
                .sorted(Comparator.comparingLong(ProductSales::unitsSold).reversed())
                .limit(5)
                .toList();
        return new Summary(ordersPlaced.get(), ordersFailed.get(), totalRevenue, topProducts);
    }

    public record ProductSales(Long productId, long unitsSold) {}

    public record Summary(long ordersPlaced, long ordersFailed, BigDecimal totalRevenue, List<ProductSales> topProducts) {}
}
