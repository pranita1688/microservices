# Testing Guide — Run the Whole Platform Locally

This walks through bringing the full stack up on your machine and exercising
every part of it: registration/login, browsing, cart, checkout (both the
success and the forced-failure path), and the Kafka-driven side effects.

## Prerequisites

- **Docker Desktop** (with Docker Compose v2 — `docker compose version`
  should work). This is the only hard requirement; Maven/Java are not needed
  on your host, the build happens inside containers.
- Optional, for the automated script: `curl` and `jq` (bash version), or
  just PowerShell (Windows version, no extra installs).
- Ports free on your host: `5432, 6379, 8080-8090, 9092`.

## 1. Start everything

From the repository root:

```bash
docker compose up -d --build
```

This builds all 10 Java services (each via its own multi-stage Dockerfile)
plus the nginx-hosted demo storefront, and starts Postgres, Redis, and Kafka
first with health checks gating the app containers' startup order. **First
run takes several minutes** (Maven downloads dependencies inside each build
stage). Watch progress with:

```bash
docker compose logs -f --tail=50
```

Check everything is healthy:

```bash
docker compose ps
```

Every app service also exposes a health endpoint, e.g.:

```bash
curl http://localhost:8080/actuator/health   # api-gateway
curl http://localhost:8085/actuator/health   # order-service
```

## 2. Option A — Test through the browser

Open **http://localhost:8090**. The page is pre-filled with a demo login;
use the **Register** tab first (the demo credentials don't exist until you
create them), then:

1. Register → switch to Login tab → log in.
2. Add a couple of products to the cart.
3. Click **Checkout** (leave the card dropdown on "always approved").
4. Watch the order appear under **My Orders** with status `PAID`.
5. Copy the order id into the **Track shipment** box and click it — this
   calls `shipping-service` directly (port 8088) to prove the Kafka
   consumer picked up the event.
6. Click **Load analytics summary** — calls `analytics-service` directly
   (port 8089) to show the running order/revenue counters.
7. Try checkout again with the **"force decline"** card option selected in
   the dropdown — the order should fail with a clear error, and if you
   check `http://localhost:8084/api/inventory/1` before and after, the
   stock count is unchanged (the reservation was released).

## 3. Option B — Automated end-to-end script

This runs the exact same flow as above via the API, including the negative
test, and fails loudly (with a clear message) if any step doesn't behave as
expected.

**macOS/Linux (bash + curl + jq):**
```bash
bash scripts/test-e2e.sh
```

**Windows (PowerShell, no extra tools needed):**
```powershell
powershell -ExecutionPolicy Bypass -File scripts/test-e2e.ps1
```

Expected output ends with:
```
🎉 All end-to-end checks passed.
```

## 4. Option C — Manual walkthrough with curl

Every step below goes through the gateway at `localhost:8080` unless noted.

```bash
# 1. Register
curl -s -X POST http://localhost:8080/api/customers/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Jane Doe","email":"jane@example.com","password":"password123","address":"123 Main St"}'

# 2. Login -> copy the "token" field from the response
curl -s -X POST http://localhost:8080/api/customers/login \
  -H "Content-Type: application/json" \
  -d '{"email":"jane@example.com","password":"password123"}'

export TOKEN="<paste token here>"

# 3. Confirm a protected route rejects you without a token
curl -i http://localhost:8080/api/cart
# -> HTTP/1.1 401

# 4. Browse products (public route, no token needed)
curl -s http://localhost:8080/api/products | jq

# 5. Add product #1 to the cart (x2)
curl -s -X POST http://localhost:8080/api/cart/items \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"productId":1,"quantity":2}'

curl -s http://localhost:8080/api/cart -H "Authorization: Bearer $TOKEN" | jq

# 6. Check starting stock directly on inventory-service
curl -s http://localhost:8084/api/inventory/1 | jq

# 7. Checkout (happy path)
curl -s -X POST http://localhost:8080/api/orders \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"customerId":1,"items":[{"productId":1,"quantity":2}],"paymentMethod":"CARD"}' | jq
# -> "status": "PAID"

# 8. Stock should now be reduced by 2
curl -s http://localhost:8084/api/inventory/1 | jq

# 9. Cart should now be empty
curl -s http://localhost:8080/api/cart -H "Authorization: Bearer $TOKEN" | jq

# 10. Track the shipment Kafka created (direct call, not via gateway)
curl -s http://localhost:8088/api/shipping/<orderId> | jq

# 11. See the running analytics (direct call, not via gateway)
curl -s http://localhost:8089/api/analytics/summary | jq

# 12. Negative test: force a declined payment with the well-known test card
curl -i -X POST http://localhost:8080/api/orders \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"customerId":1,"items":[{"productId":1,"quantity":1}],"paymentMethod":"CARD","cardNumber":"4000000000000002"}'
# -> HTTP/1.1 402, and inventory for product 1 is unchanged (reservation was released)
```

## 5. Watching the Kafka event stream directly

Curious what's actually on the topic? Exec into the Kafka container and
tail it:

```bash
docker exec -it ecommerce-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic order-events --from-beginning
```

You'll see one JSON line per `ORDER_PLACED` / `ORDER_FAILED` event — see
[ARCHITECTURE.md](ARCHITECTURE.md#3-event-driven-flow-kafka) for the exact
shape.

## 6. Running a single service outside Docker (for active development)

Every service is a normal standalone Maven project. To iterate on, say,
`order-service` without rebuilding its container each time:

```bash
# Keep the infra + every OTHER service running via Docker:
docker compose up -d postgres redis kafka customer-service product-service inventory-service payment-service cart-service

# Run order-service on your host, pointing at the dockerized infra:
cd order-service
mvn spring-boot:run \
  -Dspring-boot.run.jvmArguments="-DSPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/order_db -DSPRING_DATASOURCE_USERNAME=ecommerce -DSPRING_DATASOURCE_PASSWORD=ecommerce -DSPRING_KAFKA_BOOTSTRAP_SERVERS=localhost:9092 -DPRODUCT_SERVICE_URL=http://localhost:8082 -DINVENTORY_SERVICE_URL=http://localhost:8084 -DPAYMENT_SERVICE_URL=http://localhost:8086 -DCART_SERVICE_URL=http://localhost:8083"
```

(Requires JDK 17 + Maven on your host for this mode only — the `pom.xml` in
every service is a self-contained Spring Boot project you can also just
open directly in IntelliJ/VS Code and run.)

## 7. Resetting everything

```bash
docker compose down -v   # -v also drops the Postgres/Redis/Kafka volumes
docker compose up -d --build
```

## 8. Troubleshooting

| Symptom | Likely cause / fix |
|---|---|
| `docker compose up` fails pulling `apache/kafka:3.7.0` | Check internet access; this image is on Docker Hub, no auth needed |
| A service container keeps restarting | `docker compose logs <service-name>` — usually it started before Postgres/Kafka finished their health check; Compose's `depends_on: condition: service_healthy` should prevent this, but a very slow first boot can still race it. Just re-run `docker compose up -d` once the infra containers show healthy |
| `401` on every request, even `/api/products` | You're hitting a protected route by mistake, or `JWT_SECRET` differs between `customer-service` and `api-gateway` — check both containers were started with the same value (compose sets one shared default automatically) |
| Order checkout returns `409` | The requested quantity exceeds `quantityAvailable` — check `GET /api/inventory/{productId}`, or run `docker compose down -v && docker compose up -d --build` to reset the seeded stock |
| Shipment/analytics never appear after an order | Kafka can take a couple of seconds; also confirm `notification-service`/`shipping-service`/`analytics-service` logs show `Started ...Application` — `docker compose logs shipping-service` |
