# End-to-end smoke test for the whole platform, driven through the public
# API gateway (plus two direct calls to the event-driven services - see
# ARCHITECTURE.md for why those aren't gateway-routed).
#
# Usage:  powershell -ExecutionPolicy Bypass -File scripts/test-e2e.ps1

$ErrorActionPreference = "Stop"
$Gateway = "http://localhost:8080/api"
$Shipping = "http://localhost:8088/api"
$Analytics = "http://localhost:8089/api"

function Step($msg) { Write-Host "`n== $msg ==" -ForegroundColor Cyan }
function Pass($msg) { Write-Host "  OK  $msg" -ForegroundColor Green }
function Fail($msg) { Write-Host "  FAIL  $msg" -ForegroundColor Red; exit 1 }

function Invoke-Json {
    param($Method, $Url, $Body = $null, $Headers = @{})
    try {
        if ($Body) {
            return Invoke-RestMethod -Method $Method -Uri $Url -Headers $Headers -Body ($Body | ConvertTo-Json) -ContentType "application/json"
        } else {
            return Invoke-RestMethod -Method $Method -Uri $Url -Headers $Headers
        }
    } catch {
        if ($_.Exception.Response) {
            $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $body = $reader.ReadToEnd()
            return [PSCustomObject]@{ __error = $true; __status = [int]$_.Exception.Response.StatusCode; __body = $body }
        }
        throw
    }
}

$rand = Get-Random
$email = "e2e-test-$rand@example.com"
$password = "password123"

Step "0. Waiting for api-gateway to be reachable"
$ready = $false
for ($i = 0; $i -lt 30; $i++) {
    try { Invoke-RestMethod -Uri "http://localhost:8080/actuator/health" | Out-Null; $ready = $true; break }
    catch { Start-Sleep -Seconds 2 }
}
if (-not $ready) { Fail "api-gateway never became healthy" }
Pass "gateway is up"

Step "1. Register a new customer"
$reg = Invoke-Json POST "$Gateway/customers/register" @{ name = "E2E Tester"; email = $email; password = $password; address = "1 Test St" }
if (-not $reg.id) { Fail "registration failed: $($reg | ConvertTo-Json)" }
$customerId = $reg.id
Pass "registered customer #$customerId"

Step "2. Login and obtain a JWT"
$login = Invoke-Json POST "$Gateway/customers/login" @{ email = $email; password = $password }
if (-not $login.token) { Fail "login failed: $($login | ConvertTo-Json)" }
$token = $login.token
$authHeaders = @{ Authorization = "Bearer $token" }
Pass "obtained JWT"

Step "3. Confirm the gateway rejects requests without a token"
$noAuth = Invoke-Json GET "$Gateway/cart"
if ($noAuth.__status -ne 401) { Fail "expected 401 without token, got $($noAuth.__status)" }
Pass "protected route correctly returns 401 without a JWT"

Step "4. Browse the product catalog (public, no auth needed)"
$products = Invoke-Json GET "$Gateway/products"
if ($products.Count -lt 1) { Fail "no products returned" }
$productId = $products[0].id
Pass "catalog has $($products.Count) products; using product #$productId"

Step "5. Check starting inventory for that product"
$stockBefore = (Invoke-Json GET "http://localhost:8084/api/inventory/$productId").quantityAvailable
Pass "stock before order: $stockBefore"

Step "6. Add 2 units to the cart"
Invoke-Json POST "$Gateway/cart/items" @{ productId = $productId; quantity = 2 } $authHeaders | Out-Null
$cart = Invoke-Json GET "$Gateway/cart" $null $authHeaders
$item = $cart.items | Where-Object { $_.productId -eq $productId }
if ($item.quantity -ne 2) { Fail "cart did not contain 2 units" }
Pass "cart has 2 units of product #$productId"

Step "7. Checkout (happy path - payment approved)"
$orderBody = @{ customerId = $customerId; items = @(@{ productId = $productId; quantity = 2 }); paymentMethod = "CARD" }
$order = Invoke-Json POST "$Gateway/orders" $orderBody $authHeaders
if ($order.status -ne "PAID") { Fail "expected order status PAID, got: $($order | ConvertTo-Json)" }
$orderId = $order.id
Pass "order #$orderId placed and PAID"

Step "8. Confirm inventory was permanently decremented (reserve+confirm)"
Start-Sleep -Seconds 1
$stockAfter = (Invoke-Json GET "http://localhost:8084/api/inventory/$productId").quantityAvailable
if ($stockAfter -ne ($stockBefore - 2)) { Fail "expected stock $($stockBefore - 2), got $stockAfter" }
Pass "stock correctly reduced to $stockAfter"

Step "9. Confirm the cart was cleared after checkout"
$cartAfter = Invoke-Json GET "$Gateway/cart" $null $authHeaders
if ($cartAfter.items.Count -ne 0) { Fail "expected empty cart after checkout" }
Pass "cart is empty after checkout"

Step "10. Confirm shipping-service created a shipment via Kafka (may need a moment)"
$shipment = $null
for ($i = 0; $i -lt 10; $i++) {
    $shipment = Invoke-Json GET "$Shipping/shipping/$orderId"
    if (-not $shipment.__error) { break }
    Start-Sleep -Seconds 1
}
if ($shipment.__error) { Fail "no shipment created for order #$orderId after 10s" }
Pass "shipment created: $($shipment | ConvertTo-Json -Compress)"

Step "11. Confirm analytics-service picked up the event via Kafka"
Start-Sleep -Seconds 1
$summary = Invoke-Json GET "$Analytics/analytics/summary"
if ($summary.ordersPlaced -lt 1) { Fail "analytics never recorded a placed order: $($summary | ConvertTo-Json)" }
Pass "analytics summary: $($summary | ConvertTo-Json -Compress)"

Step "12. Negative test - force a declined payment and confirm the compensating transaction"
$stockBeforeFail = (Invoke-Json GET "http://localhost:8084/api/inventory/$productId").quantityAvailable
$failBody = @{ customerId = $customerId; items = @(@{ productId = $productId; quantity = 1 }); paymentMethod = "CARD"; cardNumber = "4000000000000002" }
$failResult = Invoke-Json POST "$Gateway/orders" $failBody $authHeaders
if ($failResult.__status -ne 402) { Fail "expected HTTP 402 for declined payment, got $($failResult.__status)" }
Pass "declined payment correctly returned HTTP 402"

Start-Sleep -Seconds 1
$stockAfterFail = (Invoke-Json GET "http://localhost:8084/api/inventory/$productId").quantityAvailable
if ($stockAfterFail -ne $stockBeforeFail) { Fail "stock was not released after declined payment (before=$stockBeforeFail after=$stockAfterFail)" }
Pass "stock correctly released back to $stockAfterFail after the declined payment (saga compensation worked)"

Write-Host "`nAll end-to-end checks passed." -ForegroundColor Green
