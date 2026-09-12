# ShopMicro — E-Commerce Microservices Platform

A production-shaped (but deliberately not over-engineered) e-commerce backend
built as independent Java/Spring Boot microservices, wired together with an
API gateway, Kafka event bus, and per-service databases. It's meant to be a
clear, runnable reference for the microservices patterns that show up in real
systems: JWT auth at the edge, a synchronous checkout saga with compensation,
and an asynchronous event stream for anything that doesn't need to be on the
critical path of a purchase.

Everything runs locally with a single `docker compose up`. See
[**TESTING.md**](TESTING.md) for the full step-by-step walkthrough.

---

## 1. Architecture

```
                         ┌───────────────────────┐
                         │   Web / Mobile Client  │   (web-app/ demo storefront,
                         │  (browser, curl, apps) │    or any HTTP client)
                         └───────────┬────────────┘
                                     │
                                     ▼
                         ┌───────────────────────┐
                         │      CDN / WAF         │   Not deployed locally —
                         │  (production concern)  │   see §6 "What's out of scope"
                         └───────────┬────────────┘
                                     │
                                     ▼
                         ┌───────────────────────┐
                         │      API Gateway        │  Spring Cloud Gateway :8080
                         │  • JWT authentication    │
                         │  • Per-IP rate limiting  │
                         │  • Path-based routing    │
                         └───────────┬────────────┘
                                     │
              ┌──────────────────────┼──────────────────────┐
              │                      │                      │
              ▼                      ▼                      ▼
        ┌───────────┐          ┌───────────┐          ┌───────────┐
        │ Product   │          │ Customer  │          │   Cart    │
        │ Service   │          │ Service   │          │ Service   │
        │  :8082    │          │  :8081    │          │  :8083    │
        └─────┬─────┘          └─────┬─────┘          └─────┬─────┘
              │                      │                      │
              ▼                      ▼                      ▼
         product_db             customer_db               Redis
        (Postgres)             (Postgres)              (cart hash)


                                     │  (checkout only)
                                     ▼
                              ┌───────────┐
                              │  Order    │  :8085 — the saga orchestrator.
                              │  Service  │  Calls the three services below
                              └─────┬─────┘  synchronously, in order.
              ┌──────────────────────┼──────────────────────┐
              │                      │                      │
              ▼                      ▼                      ▼
        ┌───────────┐          ┌───────────┐          ┌───────────┐
        │ Inventory │          │ Payment   │          │  (Cart,   │
        │ Service   │          │ Service   │          │ cleared   │
        │  :8084    │          │  :8086    │          │ on success)│
        └─────┬─────┘          └─────┬─────┘          └───────────┘
              │                      │
              ▼                      ▼
         inventory_db            payment_db


                    order-service also publishes to:
                         ┌────────────────────┐
                         │  Kafka event bus    │  topic: order-events
                         │      :9092          │
                         └─────────┬───────────┘
                                   │
              ┌────────────────────┼────────────────────┐
              │                    │                    │
              ▼                    ▼                    ▼
        ┌────────────┐      ┌────────────┐       ┌────────────┐
        │Notification│      │  Shipping  │       │ Analytics  │
        │  Service   │      │  Service   │       │  Service   │
        │   :8087    │      │   :8088    │       │   :8089    │
        │ (logs only)│      │(shipping_db)│      │(in-memory) │
        └────────────┘      └────────────┘       └────────────┘
```

**Why two tiers of services under the gateway?** Product, Customer and Cart
are reachable directly by end users. Inventory and Payment are *not*
gateway-routed — they're internal services that only `order-service` talks
to, which is why they don't appear as gateway routes even though they run as
independent, independently-deployable services with their own databases (you
can still hit them directly on `localhost:8084` / `:8086` for testing).

**Why is checkout synchronous but the rest is Kafka?** Checkout (reserve
stock → charge card → confirm/release) needs a definite yes/no answer before
the API can respond to the customer, so `order-service` calls Inventory and
Payment directly (orchestration). Everything *downstream* of a completed
order — sending a confirmation, creating a shipment, updating dashboards —
doesn't need to block the checkout response, so those three services just
subscribe to the `order-events` Kafka topic (choreography).

---

## 2. Services at a glance

| Service | Port | Datastore | Responsibility |
|---|---|---|---|
| **api-gateway** | 8080 | Redis (rate-limit counters) | Single entry point: JWT validation, rate limiting, routing |
| **customer-service** | 8081 | Postgres `customer_db` | Registration, login, JWT issuance, profile |
| **product-service** | 8082 | Postgres `product_db` | Product catalog (CRUD) |
| **cart-service** | 8083 | Redis | Per-customer shopping cart |
| **inventory-service** | 8084 | Postgres `inventory_db` | Stock levels; reserve/confirm/release holds |
| **order-service** | 8085 | Postgres `order_db` | Checkout orchestration (the saga), order history |
| **payment-service** | 8086 | Postgres `payment_db` | Mock payment processing |
| **notification-service** | 8087 | — | Consumes order events, logs simulated emails |
| **shipping-service** | 8088 | Postgres `shipping_db` | Consumes order events, creates shipments/tracking |
| **analytics-service** | 8089 | in-memory | Consumes order events, rolls up revenue/order counts |
| **web-app** | 8090 | — | Static demo storefront (plain HTML/JS) exercising the whole flow |

Every service exposes `GET /actuator/health`.

---

## 3. Key features

- **JWT authentication at the edge** — `customer-service` issues HS256 JWTs
  on login; `api-gateway` is the only service that ever validates a JWT. It
  translates a valid token into `X-User-Id` / `X-User-Email` headers, so
  every downstream service stays completely stateless with respect to auth.
- **Per-IP rate limiting** — token-bucket limiting (20 req/s, burst 40) via
  Spring Cloud Gateway's Redis-backed `RequestRateLimiter`, applied to every
  route.
- **Checkout saga with compensation** — `order-service` reserves stock,
  charges payment, then either *confirms* the stock reservation (permanent
  deduction) or *releases* it (compensating transaction) depending on
  whether payment succeeded. A reservation is never left dangling.
- **Optimistic + pessimistic stock control** — `inventory-service` locks the
  row being reserved (`SELECT ... FOR UPDATE`) so two concurrent checkouts
  can't both oversell the last unit.
- **Event-driven side effects** — `order-events` on Kafka fans out to
  notifications, shipping, and analytics without coupling `order-service` to
  any of them; each consumer is independently scalable and independently
  replaceable.
- **Deterministic failure testing** — pass the well-known test card number
  `4000000000000002` to `order-service` to force a declined payment on
  demand and watch the compensation path run for real, not just in theory.
- **Database-per-service** — every service that needs persistence owns its
  own logical database (`customer_db`, `product_db`, `inventory_db`,
  `order_db`, `payment_db`, `shipping_db`), even though they share one
  Postgres container locally to keep the laptop footprint small.
- **Idempotent event consumers** — `shipping-service` checks for an existing
  shipment before creating one, since Kafka's at-least-once delivery can
  redeliver a message.
- **Containerized end to end** — every service has its own multi-stage
  Dockerfile (Maven build stage → slim JRE runtime stage, non-root user);
  `docker-compose.yml` wires the whole stack together with health-check-gated
  startup ordering.

---

## 4. Tech stack

- **Java 17**, **Spring Boot 3.2**
- **Spring Cloud Gateway 2023.0** (reactive) for the edge
- **Spring Data JPA** + **PostgreSQL 16** for relational services
- **Spring Data Redis** for the cart and the gateway's rate limiter
- **Spring Kafka** + **Apache Kafka 3.7** (KRaft mode, no Zookeeper) for the event bus
- **jjwt** for JWT issuance/validation, **BCrypt** for password hashing
- **Docker Compose** for local orchestration
- Plain **HTML/CSS/JS** demo storefront (no build step, no framework)

---

## 5. Repository layout

```
microservices/
├── docker-compose.yml         # the entire local stack
├── init-db/                   # creates one Postgres DB per service on first boot
├── scripts/
│   ├── test-e2e.sh            # end-to-end smoke test (bash + curl + jq)
│   └── test-e2e.ps1           # same test, PowerShell (no extra deps)
├── web-app/                   # static demo storefront served by nginx
├── api-gateway/
├── customer-service/
├── product-service/
├── cart-service/
├── inventory-service/
├── order-service/
├── payment-service/
├── notification-service/
├── shipping-service/
├── analytics-service/
├── ARCHITECTURE.md            # deeper dive: request flows, saga, event contract
└── TESTING.md                 # step-by-step local test guide
```

Each service directory is a **standalone Maven project** (own `pom.xml`,
own `Dockerfile`) — there's no multi-module reactor build, so you can build
and run any one service in isolation.

---

## 6. What's out of scope (and why)

This is meant to be readable end to end, not a checklist of every
distributed-systems pattern. Deliberately left out, with the production
answer noted:

| Not implemented here | Production approach |
|---|---|
| CDN / WAF | CloudFront+AWS WAF, Cloudflare, Fastly — an edge/infra concern, not application code |
| Service discovery (Eureka/Consul) | Docker Compose DNS is discovery-enough for one host; production uses Kubernetes Services or a service mesh |
| Centralized config server | Env vars per service, as in `docker-compose.yml`; production would use Spring Cloud Config / Vault / K8s ConfigMaps |
| Distributed tracing (Zipkin/Jaeger) | Would add `micrometer-tracing` + an exporter; skipped to keep the stack lighter |
| Schema registry for Kafka payloads | Plain JSON on the topic, documented in ARCHITECTURE.md; production would use Avro/Protobuf + Confluent Schema Registry |
| TLS between services | Terminates at the edge only, in this demo; a mesh or per-service TLS would close that gap in production |
| Real payment gateway | `payment-service` is a deterministic mock (see §3) so the whole saga is testable without external accounts |

---

## 7. Quick start

```bash
docker compose up -d --build
```

First boot takes a few minutes (10 Maven builds + Postgres/Kafka image
pulls). Then either:

- open **http://localhost:8090** for the demo storefront, or
- run the automated smoke test: `bash scripts/test-e2e.sh` (or
  `scripts/test-e2e.ps1` on Windows).

Full instructions, prerequisites, and manual `curl` walkthroughs are in
**[TESTING.md](TESTING.md)**.

---

## 8. License

This is a learning/reference project — use it however is useful to you.
