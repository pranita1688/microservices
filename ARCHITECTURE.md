# Architecture Deep-Dive

See [README.md](README.md) for the high-level diagram and service table. This
document covers the request flows, the checkout saga, and the Kafka event
contract in enough detail to modify or extend the system confidently.

## 1. Authentication flow

```
Client                api-gateway              customer-service
  │                        │                          │
  ├─POST /api/customers/register───────────────────────▶│  (public route)
  │                        │                          │  BCrypt-hash password, save
  │◀──────────────────────────────────── 201 Customer ─┤
  │                        │                          │
  ├─POST /api/customers/login──────────────────────────▶│  (public route)
  │                        │                          │  verify BCrypt hash,
  │                        │                          │  sign JWT (HS256, 1h expiry)
  │◀──────────────── 200 {token, customerId, name} ────┤
  │                        │
  ├─GET /api/cart  (Authorization: Bearer <token>)─────▶│  JwtAuth filter:
  │                        │   • verifies signature + expiry against JWT_SECRET
  │                        │   • extracts subject (customerId) + email claim
  │                        │   • forwards request with X-User-Id / X-User-Email
  │                        ├─────────────────────────────▶ cart-service
  │◀─────────────────────────────────────── 200 Cart ──┤
```

- **One shared secret** (`JWT_SECRET` env var) is used by `customer-service`
  to *sign* tokens and by `api-gateway` to *verify* them. No other service
  ever sees a JWT — they trust the `X-User-Id` header the gateway attaches.
  That's the whole point of terminating auth at the edge: `cart-service` and
  `order-service` don't carry any JWT-parsing code at all.
- Routes are split into public (`/api/customers/register`, `/api/customers/login`,
  all of `/api/products/**`) and protected (`/api/cart/**`, `/api/orders/**`,
  the rest of `/api/customers/**`) in `api-gateway/src/main/resources/application.yml`.
  A request to a protected route with a missing/invalid/expired token gets a
  `401` straight from the gateway — it never reaches the backend service.

## 2. Checkout saga (the interesting part)

`order-service` orchestrates checkout as one synchronous flow so it can give
the customer a definite success/failure answer. It is **not** a distributed
transaction (there's no 2PC) — it's a sequence of local transactions with an
explicit compensating action if a later step fails.

```
POST /api/orders  { customerId, items[], paymentMethod, cardNumber? }
        │
        ▼
1. Price the order — call product-service for each line item's current
   price and name; reject if any product doesn't exist. Save the Order
   (status = PENDING) to get an order id.
        │
        ▼
2. Reserve stock — POST inventory-service /api/inventory/reserve with all
   line items. inventory-service locks each row (SELECT ... FOR UPDATE),
   checks quantityAvailable >= requested, and if so moves the units from
   "available" into "reserved" — atomically, so two simultaneous checkouts
   can't both succeed against the last unit.
        │
        ├── insufficient stock ──▶ mark order STOCK_UNAVAILABLE,
        │                          publish ORDER_FAILED, return 409
        ▼
3. Charge payment — POST payment-service /api/payments/charge with the
   order id and total. payment-service is a deterministic mock: it declines
   only if cardNumber == "4000000000000002", otherwise approves.
        │
        ├── declined ──▶ RELEASE the stock reservation (moves the units
        │                back from "reserved" to "available" — the
        │                compensating transaction), mark order
        │                PAYMENT_FAILED, publish ORDER_FAILED, return 402
        ▼
4. Confirm stock — POST inventory-service /api/inventory/confirm. This
   permanently removes the reserved units (the sale is final). Mark order
   PAID.
        │
        ▼
5. Clear the customer's cart (best-effort — a failure here does not fail
   the already-successful order) and publish ORDER_PLACED.
        │
        ▼
   Return 201 with the full order (status PAID).
```

**Why "reserve then confirm/release" instead of decrementing stock
directly in step 2?** It's the standard way to avoid selling stock that a
payment failure would otherwise have to "give back" via a second write with
its own failure modes. Reserving first means the only compensating action
needed is a release, and the release is idempotent-safe by construction
(it can't take `quantityReserved` below zero).

**Testing both branches on purpose:** pass `"cardNumber": "4000000000000002"`
in the order request to force the decline branch deterministically — see
[TESTING.md](TESTING.md) step 9.

## 3. Event-driven flow (Kafka)

Once an order reaches a terminal state, `order-service` publishes one JSON
message to the `order-events` topic (partition key = `orderId`, so all
events for one order stay in order):

```json
{
  "eventType": "ORDER_PLACED",
  "orderId": 42,
  "customerId": 7,
  "items": [{ "productId": 1, "productName": "Wireless Headphones", "quantity": 2, "unitPrice": 79.99 }],
  "totalAmount": 159.98,
  "status": "PAID",
  "reason": null,
  "timestamp": "2026-09-12T10:15:30Z"
}
```

`eventType` is either `ORDER_PLACED` (payment succeeded) or `ORDER_FAILED`
(stock unavailable or payment declined; `reason` is populated). Three
independent consumer groups read the same topic:

| Consumer | Group id | Reacts to | Effect |
|---|---|---|---|
| notification-service | `notification-service` | both event types | Logs a simulated confirmation/failure email |
| shipping-service | `shipping-service` | `ORDER_PLACED` only | Creates a `Shipment` row with a generated tracking id (skips if one already exists for that order — see idempotency note below) |
| analytics-service | `analytics-service` | both event types | Increments in-memory counters: orders placed/failed, revenue, units sold per product |

**Why plain JSON strings instead of Avro/a shared library?** Each consumer
keeps its own local copy of the `OrderEvent` shape (see
`*/model/OrderEvent.java` or `*/listener/OrderEvent.java` in each consumer
service) and only reads the fields it needs. That's a deliberate
microservices trade-off: no shared JAR to version and redeploy in lockstep,
at the cost of the contract only being enforced by convention + this
document. A larger system would put a schema registry in front of this.

**Idempotency:** Kafka's default delivery guarantee is at-least-once, so any
consumer must tolerate reprocessing the same message (e.g. after a consumer
restart before it commits an offset). `shipping-service` handles this by
checking `findByOrderId` before inserting; `notification-service` and
`analytics-service` are safe to reprocess by nature (logging and counters —
for a real system you'd want exactly-once counting, which this demo
intentionally doesn't attempt).

## 4. Data ownership

Each service owns its data exclusively — no service ever queries another
service's database directly, only its REST API. Concretely:

- `customer_db` — `customers`
- `product_db` — `products` (seeded with 6 demo products on first boot)
- `inventory_db` — `inventory_items` (seeded 1:1 with the demo products)
- `order_db` — `orders`, `order_items`
- `payment_db` — `payments`
- `shipping_db` — `shipments`
- Redis — cart hashes (`cart:{customerId}`, TTL 72h) and the gateway's rate-limit token buckets
- analytics-service — in-memory only (resets on restart; see its `application.yml` for the trade-off note)

All six Postgres databases live in **one Postgres container** locally
(`init-db/init-multiple-dbs.sh` creates them on first boot) purely to keep
resource usage sane on a laptop. Each service still only ever connects to
its own database — nothing stops you from splitting them into six separate
containers (or six separate RDS instances) later; that's exactly the point
of the database-per-service pattern.

## 5. Resilience notes (what's real, what's simplified)

- **Timeouts**: `order-service`'s `RestTemplate` has a 3s connect / 5s read
  timeout on its calls to Product/Inventory/Payment/Cart, so a hung
  dependency can't hang checkout forever.
- **No retries / circuit breaker**: a production build would wrap the
  Inventory/Payment calls in Resilience4j (retry + circuit breaker) —
  omitted here to keep the saga logic easy to read in one pass.
- **Compensation is direct, not outboxed**: the `release` call on payment
  decline is a second synchronous HTTP call. If *that* call itself fails,
  the reservation is left dangling (documented in code with a comment). A
  production system would use the transactional outbox pattern + a retry
  worker instead of a bare synchronous call for compensation.
- **Kafka publish failure never fails the order**: by the time `order-events`
  is published, the order is already durably committed in `order_db`. A
  Kafka outage delays notifications/shipping/analytics, but never blocks or
  reverses a completed purchase.
