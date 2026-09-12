#!/usr/bin/env bash
# End-to-end smoke test for the whole platform, driven entirely through the
# public API gateway (plus two direct calls to the event-driven services,
# since those are intentionally not gateway-routed - see ARCHITECTURE.md).
#
# Requires: curl, jq
# Usage:    bash scripts/test-e2e.sh
set -euo pipefail

GATEWAY="http://localhost:8080/api"
SHIPPING="http://localhost:8088/api"
ANALYTICS="http://localhost:8089/api"

pass() { echo "  ✅ $1"; }
fail() { echo "  ❌ $1"; exit 1; }
step() { echo; echo "== $1 =="; }

need() { command -v "$1" >/dev/null 2>&1 || { echo "Missing dependency: $1"; exit 1; }; }
need curl
need jq

RAND=$RANDOM
EMAIL="e2e-test-$RAND@example.com"
PASSWORD="password123"

step "0. Waiting for api-gateway to be reachable"
for i in $(seq 1 30); do
  if curl -sf "http://localhost:8080/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 2
  if [ "$i" -eq 30 ]; then fail "api-gateway never became healthy"; fi
done
pass "gateway is up"

step "1. Register a new customer"
REGISTER_RESPONSE=$(curl -s -X POST "$GATEWAY/customers/register" \
  -H "Content-Type: application/json" \
  -d "{\"name\":\"E2E Tester\",\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\",\"address\":\"1 Test St\"}")
CUSTOMER_ID=$(echo "$REGISTER_RESPONSE" | jq -r '.id')
[ "$CUSTOMER_ID" != "null" ] || fail "registration failed: $REGISTER_RESPONSE"
pass "registered customer #$CUSTOMER_ID"

step "2. Login and obtain a JWT"
LOGIN_RESPONSE=$(curl -s -X POST "$GATEWAY/customers/login" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASSWORD\"}")
TOKEN=$(echo "$LOGIN_RESPONSE" | jq -r '.token')
[ "$TOKEN" != "null" ] || fail "login failed: $LOGIN_RESPONSE"
pass "obtained JWT"
AUTH=(-H "Authorization: Bearer $TOKEN")

step "3. Confirm the gateway rejects requests without a token"
STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$GATEWAY/cart")
[ "$STATUS" == "401" ] || fail "expected 401 without token, got $STATUS"
pass "protected route correctly returns 401 without a JWT"

step "4. Browse the product catalog (public, no auth needed)"
PRODUCTS=$(curl -s "$GATEWAY/products")
PRODUCT_COUNT=$(echo "$PRODUCTS" | jq 'length')
[ "$PRODUCT_COUNT" -gt 0 ] || fail "no products returned"
PRODUCT_ID=$(echo "$PRODUCTS" | jq -r '.[0].id')
pass "catalog has $PRODUCT_COUNT products; using product #$PRODUCT_ID"

step "5. Check starting inventory for that product"
STOCK_BEFORE=$(curl -s "http://localhost:8084/api/inventory/$PRODUCT_ID" | jq -r '.quantityAvailable')
pass "stock before order: $STOCK_BEFORE"

step "6. Add 2 units to the cart"
curl -s -X POST "$GATEWAY/cart/items" "${AUTH[@]}" -H "Content-Type: application/json" \
  -d "{\"productId\":$PRODUCT_ID,\"quantity\":2}" >/dev/null
CART=$(curl -s "$GATEWAY/cart" "${AUTH[@]}")
CART_QTY=$(echo "$CART" | jq -r ".items[] | select(.productId==$PRODUCT_ID) | .quantity")
[ "$CART_QTY" == "2" ] || fail "cart did not contain 2 units: $CART"
pass "cart has 2 units of product #$PRODUCT_ID"

step "7. Checkout (happy path — payment approved)"
ORDER_RESPONSE=$(curl -s -X POST "$GATEWAY/orders" "${AUTH[@]}" -H "Content-Type: application/json" \
  -d "{\"customerId\":$CUSTOMER_ID,\"items\":[{\"productId\":$PRODUCT_ID,\"quantity\":2}],\"paymentMethod\":\"CARD\"}")
ORDER_ID=$(echo "$ORDER_RESPONSE" | jq -r '.id')
ORDER_STATUS=$(echo "$ORDER_RESPONSE" | jq -r '.status')
[ "$ORDER_STATUS" == "PAID" ] || fail "expected order status PAID, got: $ORDER_RESPONSE"
pass "order #$ORDER_ID placed and PAID"

step "8. Confirm inventory was permanently decremented (reserve+confirm)"
sleep 1
STOCK_AFTER=$(curl -s "http://localhost:8084/api/inventory/$PRODUCT_ID" | jq -r '.quantityAvailable')
EXPECTED=$((STOCK_BEFORE - 2))
[ "$STOCK_AFTER" == "$EXPECTED" ] || fail "expected stock $EXPECTED, got $STOCK_AFTER"
pass "stock correctly reduced to $STOCK_AFTER"

step "9. Confirm the cart was cleared after checkout"
CART_AFTER=$(curl -s "$GATEWAY/cart" "${AUTH[@]}")
CART_LEN=$(echo "$CART_AFTER" | jq '.items | length')
[ "$CART_LEN" == "0" ] || fail "expected empty cart after checkout, got: $CART_AFTER"
pass "cart is empty after checkout"

step "10. Confirm shipping-service created a shipment via Kafka (may need a moment)"
for i in $(seq 1 10); do
  SHIPMENT=$(curl -s -o /tmp/shipment.json -w "%{http_code}" "$SHIPPING/shipping/$ORDER_ID")
  if [ "$SHIPMENT" == "200" ]; then break; fi
  sleep 1
done
[ "$SHIPMENT" == "200" ] || fail "no shipment created for order #$ORDER_ID after 10s"
pass "shipment created: $(cat /tmp/shipment.json 2>/dev/null || true)"

step "11. Confirm analytics-service picked up the event via Kafka"
sleep 1
SUMMARY=$(curl -s "$ANALYTICS/analytics/summary")
ORDERS_PLACED=$(echo "$SUMMARY" | jq -r '.ordersPlaced')
[ "$ORDERS_PLACED" -ge 1 ] || fail "analytics never recorded a placed order: $SUMMARY"
pass "analytics summary: $SUMMARY"

step "12. Negative test — force a declined payment and confirm the compensating transaction"
STOCK_BEFORE_FAIL=$(curl -s "http://localhost:8084/api/inventory/$PRODUCT_ID" | jq -r '.quantityAvailable')
FAIL_ORDER_RESPONSE=$(curl -s -w "\n%{http_code}" -X POST "$GATEWAY/orders" "${AUTH[@]}" -H "Content-Type: application/json" \
  -d "{\"customerId\":$CUSTOMER_ID,\"items\":[{\"productId\":$PRODUCT_ID,\"quantity\":1}],\"paymentMethod\":\"CARD\",\"cardNumber\":\"4000000000000002\"}")
FAIL_STATUS_CODE=$(echo "$FAIL_ORDER_RESPONSE" | tail -n1)
[ "$FAIL_STATUS_CODE" == "402" ] || fail "expected HTTP 402 for declined payment, got $FAIL_STATUS_CODE"
pass "declined payment correctly returned HTTP 402"

sleep 1
STOCK_AFTER_FAIL=$(curl -s "http://localhost:8084/api/inventory/$PRODUCT_ID" | jq -r '.quantityAvailable')
[ "$STOCK_AFTER_FAIL" == "$STOCK_BEFORE_FAIL" ] || fail "stock was not released after declined payment (before=$STOCK_BEFORE_FAIL after=$STOCK_AFTER_FAIL)"
pass "stock correctly released back to $STOCK_AFTER_FAIL after the declined payment (saga compensation worked)"

echo
echo "🎉 All end-to-end checks passed."
