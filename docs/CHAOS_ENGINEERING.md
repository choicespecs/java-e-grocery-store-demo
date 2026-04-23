# Chaos Engineering — Fault Injection Reference

This document covers every failure scenario the application can simulate, what happens inside the checkout saga at each point, and what a production system would do differently.

---

## Contents

1. [How Fault Injection Works](#how-fault-injection-works)
2. [The Checkout Saga](#the-checkout-saga)
3. [Service Profiles](#service-profiles)
   - [Inventory Service](#inventory-service)
   - [Payment Service](#payment-service)
   - [Promotion Service](#promotion-service)
   - [Coupon / Offer Engine](#coupon--offer-engine)
4. [Compensation Mechanics](#compensation-mechanics)
5. [Failure Scenarios and Remediation](#failure-scenarios-and-remediation)
   - [How to Read System State](#how-to-read-system-state)
   - [Scenario A: Payment Declined or Service Down](#scenario-a-payment-declined-or-service-down)
   - [Scenario B: Payment Timeout — The Ambiguous Case](#scenario-b-payment-timeout--the-ambiguous-case)
   - [Scenario C: Payment Succeeds, Order Persist Fails](#scenario-c-payment-succeeds-order-persist-fails)
   - [Scenario D: Full 3-Way Rollback — Inventory Commit Fails](#scenario-d-full-3-way-rollback--inventory-commit-fails)
   - [Scenario E: Double Submission — Silent Double Charge](#scenario-e-double-submission--silent-double-charge)
   - [Scenario F: Compensation Chain Breaks Mid-Way](#scenario-f-compensation-chain-breaks-mid-way)
   - [Scenario G: Silent Discount Loss](#scenario-g-silent-discount-loss)
   - [Known Data Inconsistency After Rollback](#known-data-inconsistency-after-rollback)
6. [Design Patterns Illustrated](#design-patterns-illustrated)
7. [Preset Quick Reference](#preset-quick-reference)

---

## How Fault Injection Works

All fault behaviour is controlled by **`DemoFaultConfig`** — a singleton `@Component` holding `volatile` fields for each service. Stubs read these fields on every call. The Chaos Panel at `/demo` writes them.

**`FaultInjector.apply(mode, label, slowMs, flakyCounter)`** is the single dispatch point called inside every stub:

| Mode | Behaviour | Thread impact |
|------|-----------|---------------|
| `NORMAL` | No-op — passes through immediately | None |
| `DOWN` | Throws `ServiceUnavailableException` immediately | None — returns fast |
| `SLOW` | `Thread.sleep(slowDelayMs)` before returning | **Blocks the caller thread for the full delay** |
| `FLAKY` | Increments a counter; throws on every even-numbered call | None on odd calls; throws on even |

`SLOW` is the most dangerous mode in practice: it ties up the thread making the call. For services running on the servlet request thread (inventory, promotion, coupon engine), a slow response exhausts the thread pool under load. Payment avoids this by running on a **dedicated daemon thread with a hard timeout** — the only service in this application with that protection.

`FLAKY` uses a shared `AtomicInteger` counter per service. The counter persists across requests, so the first checkout fails, the second succeeds, the third fails, etc. Resetting to `all-normal` resets all counters.

---

## The Checkout Saga

The checkout saga is a sequence of calls across three external services. Because each call may have side effects, and because a single database transaction cannot span external services, each step that changes state defines an explicit **compensating action** to undo it if a later step fails.

```
HAPPY PATH
──────────────────────────────────────────────────────────────────
 Step 1  inventoryService.checkAvailability()
         └─ Read-only preflight. No stock held. No charge.
            FAIL → immediate abort, zero side effects.

 Step 2  promotionService.applyPromotion()      [if promo code entered]
         └─ Read-only. Validates code, calculates discount.
            FAIL + graceful-degrade ON  → DEGRADED, continue at full price.
            FAIL + graceful-degrade OFF → abort, zero side effects.

 Step 3  inventoryService.reserveStock()        ← SIDE EFFECT
         └─ Stock units held. Other sessions cannot buy these units.
            FAIL → abort (payment not yet attempted, no compensation needed).

 Step 4  paymentService.processPayment()        ← SIDE EFFECT (runs on separate thread)
         └─ Card charged.
            FAIL / TIMEOUT / DECLINE →
              COMPENSATE: inventoryService.releaseReservation()

 Step 5  orderService.createOrder()             ← SIDE EFFECT
         └─ Order written to DB.
            FAIL →
              COMPENSATE: paymentService.refund()
              COMPENSATE: inventoryService.releaseReservation()

 Step 6  inventoryService.commitReservation()   ← SIDE EFFECT
         └─ Reservation made permanent.
            FAIL →
              COMPENSATE: paymentService.refund()
              COMPENSATE: orderService.cancelOrder()
              COMPENSATE: inventoryService.releaseReservation()
──────────────────────────────────────────────────────────────────
```

**Key rule:** compensations fire in reverse step order. `releaseReservation` is never fault-injected — compensations must be reliable, or a failed saga leaves stock permanently locked and money taken without an order.

**`CheckoutService` is intentionally not `@Transactional`** — the saga spans external services that cannot be enrolled in a single JDBC transaction.

---

## Service Profiles

### Inventory Service

The inventory service has **three independent fault points**, one per saga step. `releaseReservation` is excluded from fault injection because it is only ever called as a compensation — making compensations fallible defeats the purpose of the saga.

#### Fault point 1 — `checkAvailability` (Step 1)

A read-only pre-flight check. No stock is reserved, no money moves.

| Mode | Saga outcome | What the user sees | Side effects |
|------|-------------|-------------------|--------------|
| DOWN | Step 1 FAILED → immediate abort | "Service unavailable — safe to retry" | None |
| FLAKY | Alternates: Step 1 OK / Step 1 FAILED | Alternates: proceeds / "service unavailable" | None |
| SLOW | Step 1 blocks servlet thread for `slowDelayMs`; then continues | Browser spinner for N seconds, then success | Thread held |

**No compensation is needed for any failure here** — the saga has not yet produced any side effects.

**Production recommendation:** A read-only availability check should have its own timeout and a circuit breaker. A slow inventory service at Step 1 will stall every checkout attempt and exhaust the servlet thread pool. Use Resilience4j `@TimeLimiter` + `@CircuitBreaker` to fail fast and shed load.

---

#### Fault point 2 — `reserveStock` (Step 3)

The first step with a side effect: stock units are held, reducing availability for other sessions.

| Mode | Saga outcome | What the user sees | Side effects |
|------|-------------|-------------------|--------------|
| DOWN | Step 3 FAILED → abort | "Service unavailable" | None — payment never attempted |
| FLAKY | Alternates: Step 3 OK / Step 3 FAILED | Alternates: checkout continues / abort | None on failure |
| SLOW | Servlet thread blocked for `slowDelayMs`; then continues | Browser spinner for N seconds (typically 4s in the preset), then success | Thread held |

**The SLOW scenario at reserve is the most instructive one for thread safety.** Unlike payment, inventory runs directly on the servlet request thread — there is no `Future.get(timeout)` protecting it. Under concurrent load, a 4-second inventory reserve means a 4-second thread hold per request. With a default Tomcat pool of 200 threads, 50 concurrent checkouts would exhaust the pool and queue or reject all other requests on the server.

**Production recommendation:** Wrap every external call in a timeout, not just payment. Move `reserveStock` to a dedicated thread pool or use `CompletableFuture.orTimeout()`. Add a circuit breaker so that once the service starts timing out consistently, it opens and subsequent calls fail fast without blocking.

---

#### Fault point 3 — `commitReservation` (Step 6)

The last step. By the time this runs, the card has been charged and the order has been saved to the database.

| Mode | Saga outcome | What the user sees | Side effects before failure |
|------|-------------|-------------------|----------------------------|
| DOWN | Step 6 FAILED → 3-way compensation | "Checkout failed — payment refunded" | Payment charged, order created |
| FLAKY | Alternates: OK / 3-way compensation | Alternates: success / "payment refunded" | Payment + order on failure calls |
| SLOW | Servlet thread blocked; then continues | Browser spinner, then success | All prior steps complete |

**This is the hardest failure point in the saga.** When commit fails, three compensations must all succeed:
1. `paymentService.refund(paymentToken)` — reverse the charge
2. `orderService.cancelOrder(order)` — mark the DB order as cancelled
3. `inventoryService.releaseReservation(reservation)` — return stock to pool

If any of these compensations themselves fail, the system is in an inconsistent state. In the demo, compensations are assumed reliable. In production, they are not.

**Production recommendation:** Use an **outbox table**. Write the compensating events (refund, cancel, release) to a DB table in the same ACID transaction as the order creation. A background worker retries each event with exponential back-off until it succeeds. A JVM crash between commit failure and compensation completion is thus recoverable — the outbox survives the crash and the worker picks up on restart.

---

### Payment Service

Payment runs on a **dedicated daemon thread** with a configurable `Future.get(paymentTimeoutMs)` deadline. This is the only service in the application with this protection.

```
CheckoutService (servlet thread)
  │
  ├─ paymentExecutor.submit(() -> paymentService.processPayment(req))
  │      └─ payment-call thread: runs the payment stub
  │
  └─ paymentFuture.get(paymentTimeoutMs, MILLISECONDS)
         ├─ Returns on time  → payment OK, continue saga
         ├─ TimeoutException → cancel future, releaseReservation, throw
         └─ ExecutionException
               ├─ PaymentFailedException (decline) → releaseReservation, throw
               └─ ServiceUnavailableException       → releaseReservation, throw
```

| Mode | Saga outcome | What the user sees | Compensation |
|------|-------------|-------------------|--------------|
| DOWN | Step 4 FAILED → releaseReservation | "Payment service unavailable — no charge made" | releaseReservation |
| SLOW (`slowDelay < paymentTimeout`) | Step 4 slow but OK | Success after delay | None needed |
| SLOW (`slowDelay > paymentTimeout`) | TimeoutException → releaseReservation | "Payment timed out — no charge made" | releaseReservation |
| FLAKY | Alternates: OK / releaseReservation | Alternates: success / payment failed | releaseReservation on fail |

#### Card decline scenarios

These are not fault-injected — they use specific test card numbers against the payment stub:

| Card number | Behaviour | Saga outcome |
|-------------|-----------|-------------|
| `4111 1111 1111 1111` | Approved | Steps 4–6 complete normally |
| `4000 0000 0000 0002` | Declined | `PaymentFailedException` → releaseReservation |
| `4000 0000 0000 9995` | Insufficient funds | `PaymentFailedException` → releaseReservation |
| `4000 0000 0000 0127` | Incorrect CVC | `PaymentFailedException` → releaseReservation |

All decline paths produce the same compensation: `releaseReservation`. The stock reservation is released and no order is created.

#### Idempotency

When idempotency is **enabled** (default), the payment stub records the result of each `idempotencyKey`. On a retry with the same key, it returns the cached result instead of processing again. A browser Back-button resubmit replays the same key and gets the same "approved" result without a second charge.

When idempotency is **disabled**, the same form resubmission is treated as a fresh payment. Two separate charges appear in the payment log with no saga error — the bug is silent, which is the point of the demo.

**Production recommendation:** Generate the idempotency key once per checkout form load (a UUID is embedded as a hidden field). Store it alongside the pending order in the outbox. Real gateways (Stripe, Adyen, Braintree) accept an `Idempotency-Key` HTTP header. Always replay using the same key on retry.

**Why only payment has idempotency:** the promotion and inventory services are read-only or explicitly compensated — retrying them is safe without a key. Payment is the only call that both charges money and may receive retries without a fresh user action.

---

### Promotion Service

The promotion service validates a promo code and calculates a percentage or flat discount. It is only called when the user enters a promo code on the checkout form. **The `/demo` "Run Test" button always uses `SAVE10`** to ensure the promotion step fires on every automated test.

This service is **non-critical by design** — the store can process orders without a discount code. A toggle (`promotionGracefulDegradation`) controls whether unavailability is treated as fatal or gracefully degraded.

| Mode | Graceful degrade | Saga outcome | What the user sees |
|------|-----------------|-------------|-------------------|
| DOWN | ON | Step 2 DEGRADED → continues at full price | Order succeeds; saga trace shows "DEGRADED" |
| DOWN | OFF | Step 2 FAILED → abort (no side effects) | "Service unavailable" — no charge, safe to retry |
| FLAKY | ON | Alternates: discount applied / DEGRADED | Alternates: discounted / full price |
| FLAKY | OFF | Alternates: discount applied / abort | Alternates: success / "service unavailable" |
| SLOW | ON (preset default) | Step 2 blocks servlet thread for `slowDelayMs`; continues | Checkout pauses N seconds, then succeeds with discount |

**Promotion is read-only** — the service is queried but never mutated. This means:
- A retry after a failure is always safe, regardless of idempotency
- FLAKY + graceful degrade never requires compensation — the DEGRADED step produces no side effects to undo

**Production recommendation:** Even when degrading gracefully, emit a metric and alert. Silent degradation hides outages from on-call engineers — the customers are still buying, but at full price, and nobody knows the discount engine has been down for six hours.

Available promo codes: `SAVE10` (10%), `SAVE20` (20%), `FLAT5` ($5), `WELCOME` (15%), `FRESH` ($4), `BIG25` (25%), `HALFOFF` (50%).

---

### Coupon / Offer Engine

The coupon engine is a distinct service from the promotion service. It evaluates **cart-level offers** (BOGO, bundles, spend thresholds, multi-buys) on every cart mutation and on every cart page load. It is not part of the checkout saga — it runs in `CartController` before the user ever reaches checkout.

**It always degrades gracefully.** `CartController.refreshDiscounts()` wraps the call in a try/catch; on `ServiceUnavailableException`, it applies `CouponResult.none()` and sets a `couponServiceDown` flag on the session cart.

```
CartController.refreshDiscounts()
  ├─ cartPromotionService.evaluate(items)  ← never fault-injected, always succeeds
  └─ try:
       couponService.evaluate(items)       ← fault-injectable
       cart.applyCoupons(result)
       cart.setCouponServiceDown(false)
     catch ServiceUnavailableException:
       cart.applyCoupons(CouponResult.none())
       cart.setCouponServiceDown(true)     ← UI shows "⚡ Engine DOWN"
```

**`getOfferCatalog()` is never fault-injected.** This method returns static metadata about available offers. The cart page calls it even when the engine is DOWN, so it can display each offer with an "unavailable" badge rather than showing an empty list. This is the key UX difference between "no offers triggered" and "engine is down."

| Mode | Effect on cart page | Effect on checkout | Side effects |
|------|--------------------|--------------------|--------------|
| DOWN | ⚡ Engine DOWN badge; all offers show "unavailable" | `couponDiscount = $0` — no coupon savings applied to final total | None — cart deals still work |
| FLAKY | Badge alternates per page load; offers flip applied/unavailable | Savings applied on odd evaluations, dropped on even | None |
| SLOW | Each cart add/update/remove blocks servlet thread for `slowDelayMs` | Savings applied (if service recovers); cart interactions slow | Thread held on mutations |

**Cart promotions are a completely separate service (`CartPromotionService`) and are never fault-injected.** Deals like "Buy 3 Bananas → Save $1" always work regardless of the coupon engine's state. The two discount tiers are intentionally independent so fault injection on the coupon engine doesn't silently disable cart promotions.

Active coupon offers:

| Offer ID | Trigger | Discount |
|----------|---------|---------|
| `BOGO-CROISSANT` | Croissants × 2 | Every 2nd pack free |
| `BUNDLE-SPINACH-YOGURT` | Baby Spinach + Greek Yogurt in cart | $1.50 off |
| `MUFFIN-MULTIBUY` | Blueberry Muffins × 2 | $2.00 off |
| `COLD-BREW-SAVE3` | Cold Brew Coffee × 2 | $3.00 off |
| `BUNDLE-BREAD-CHEESE` | Sourdough Bread + Cheddar Cheese in cart | $1.50 off |
| `BAGEL-MULTIBUY` | Whole Wheat Bagels × 2 | $1.50 off |
| `BUNDLE-TEA-OJ` | Herbal Tea + Orange Juice in cart | $1.50 off |
| `SPEND20-SAVE3` | Cart subtotal ≥ $20 | $3.00 off |

Active cart deals (CartPromotionService — always on):

| Product | Trigger | Deal |
|---------|---------|------|
| Whole Milk (1 gal) | qty ≥ 3 | Buy 2, Get 1 Free |
| Organic Apples (1 lb) | qty ≥ 4 | Save $2.00 |
| Bananas (bunch) | qty ≥ 3 | Save $1.00 |
| Cheddar Cheese (8 oz) | qty ≥ 2 | Save $1.50 |
| Sparkling Water (12-pack) | qty ≥ 2 | Save $2.00 |
| Orange Juice (52 oz) | qty ≥ 3 | Save $2.00 |

**Production recommendation:** Evaluate coupon offers asynchronously on a background thread and push results to the cart via SSE or a polling endpoint. This prevents a slow or unreachable offer engine from blocking cart interactions. Cache the catalog response aggressively — it changes infrequently and is read on every page load.

---

## Compensation Mechanics

The following table maps every failure point to the exact compensations the saga fires and their ordering. Compensations always fire in reverse step order.

| Failure at | Compensations fired (in order) | Money moved? | Order created? | Stock released? |
|------------|-------------------------------|-------------|---------------|----------------|
| Step 1 — checkAvailability | None | No | No | — |
| Step 2 — applyPromotion (hard fail) | None | No | No | — |
| Step 2 — applyPromotion (graceful) | None (DEGRADED, continues) | No | No | — |
| Step 3 — reserveStock | None | No | No | — (never reserved) |
| Step 4 — processPayment (any failure) | releaseReservation | No | No | Yes |
| Step 5 — createOrder | refund → releaseReservation | Reversed | No | Yes |
| Step 6 — commitReservation | refund → cancelOrder → releaseReservation | Reversed | Cancelled | Yes |

**The compensation chain is assumed reliable in this demo.** In production, each compensation can itself fail (network timeout during refund, DB down during cancelOrder). The correct mitigation is an **outbox table**: write the compensating events to the database in the same transaction as the order attempt, then process them asynchronously with retry + exponential back-off. A JVM crash between the failed step and the compensation is then recoverable.

---

---

## Failure Scenarios and Remediation

This section walks through each meaningful failure point, the exact state of every data store at the moment of failure, what the application does automatically, what can go wrong in the recovery path, and how a real system would handle it.

### How to Read System State

After triggering a failure scenario, you can inspect state in three places:

| Where | What to look at | How to access |
|-------|----------------|---------------|
| **Saga trace** | Which steps ran, which failed, which compensated | Shown inline on the checkout error page and on the Chaos Panel after "Run Test" |
| **H2 console** | `grocery_order` table — `status` column; `grocery_order_item` table; `product` table `stock_quantity` | `http://localhost:8080/h2-console` · JDBC URL: `jdbc:h2:mem:grocerydb` · user: `sa` · no password |
| **Cart page** | `StubInventoryService.available` map state is not directly visible, but the product listing reflects it indirectly via `stockQuantity` display | `/` or `/cart` |

The authoritative stock counter lives in `StubInventoryService.available` (a `ConcurrentHashMap` in memory). The `product.stock_quantity` column in H2 is a **display cache** only — updated after confirmed orders, not at reservation time. After a rollback, these two can diverge. See [Known Data Inconsistency After Rollback](#known-data-inconsistency-after-rollback).

---

### Scenario A: Payment Declined or Service Down

**Preset:** `payment-down` | **Test card:** `4000 0000 0000 0002` (declined), `4000 0000 0000 9995` (insufficient funds)

**What triggers it:** The payment stub throws `PaymentFailedException` (card decline) or `ServiceUnavailableException` (service down). Both follow the same compensation path.

**State at moment of failure:**

| Data store | State |
|------------|-------|
| `StubInventoryService.available` | Decremented at Step 3 — units are held, not available to other sessions |
| `StubInventoryService.reservations` | Contains the active reservation record |
| `Product.stock_quantity` (H2) | Unchanged — display cache only decrements on confirmed orders |
| `grocery_order` (H2) | No row exists — Step 5 was never reached |
| Payment gateway | No charge — Step 4 failed |

**Compensation sequence:**

```
Step 4 throws PaymentFailedException / ServiceUnavailableException
  └─ CheckoutService catches in the paymentFuture.get() block
       └─ inventoryService.releaseReservation(reservation)
            └─ removes reservation from reservations map
            └─ adds quantities back to available map
```

**End state:** No order, no charge, stock fully restored. Safe to retry immediately.

**What the user sees:** An error message with the saga trace showing Step 4 as FAILED and the `releaseReservation` compensation as COMPENSATED.

**What can go wrong:**

- In production, `releaseReservation` is a call to an external inventory system. If it fails after the payment failure, the reservation stays in the `reservations` map indefinitely. Customers see the item as unavailable even though it was never sold.
- The next checkout attempt for the same product will fail at Step 1 (`checkAvailability`) because `available` is still decremented.

**Suggested fix:**

Write the compensation intent (release reservation X) to an **outbox table** in the same ACID transaction as the failed order attempt. A background worker retries the release until the inventory service confirms success. Orphaned reservations older than a configurable TTL should be automatically swept and released by a reconciliation job.

---

### Scenario B: Payment Timeout — The Ambiguous Case

**Preset:** `payment-timeout` (slowDelay=5000ms, paymentTimeout=3000ms)

**What makes this different from a simple failure:** When `Future.get(timeoutMs)` throws `TimeoutException`, the application releases the inventory reservation and returns an error. However, the payment thread is still running on the `payment-call` daemon thread — it may complete successfully after the timeout, meaning the card was actually charged even though the application reported failure.

**State at moment of timeout:**

| Data store | State |
|------------|-------|
| `StubInventoryService.available` | Was decremented at Step 3 |
| Payment gateway | **Unknown** — the charge may or may not have completed |
| `grocery_order` (H2) | No row exists |

**What the application does:**

```
paymentFuture.get(3000, MILLISECONDS) → TimeoutException
  └─ paymentFuture.cancel(true)          ← attempts to interrupt the payment thread
  └─ inventoryService.releaseReservation(reservation)
  └─ throws ServiceUnavailableException("Payment timed out")
```

**The fundamental problem:** `Future.cancel(true)` sends an interrupt, but whether the payment thread actually stops depends on whether the underlying HTTP client respects interrupts. If the charge completed on the gateway side in that window:

- The customer was charged
- The application does not know
- No order was created
- No refund was issued
- The inventory reservation was released

The customer sees a timeout error but has a pending charge on their statement.

**Suggested fix:**

This is why the idempotency key matters even on timeout. Before timing out, store the idempotency key and "payment attempted" state in the outbox. On retry (the customer tries to check out again), the same key is sent to the gateway. A gateway that received and processed the first charge will return the cached result rather than charging again. Without this, a timeout becomes a potential double-charge on retry.

Additional mitigation: implement a background **payment reconciliation job** that queries the gateway for any charges associated with the session in the last N minutes that have no corresponding `CONFIRMED` order. These orphaned charges trigger automatic refunds.

---

### Scenario C: Payment Succeeds, Order Persist Fails

**Preset:** Not directly injectable in this demo — would require introducing a DB fault. Simulated in the codebase by Step 5 throwing an exception.

**What triggers it:** The card is charged (Step 4 succeeds), but `orderService.createOrder()` throws — for example, a database constraint violation, a full tablespace, or a network partition to the DB.

**State at moment of failure:**

| Data store | State |
|------------|-------|
| `StubInventoryService.available` | Decremented — units held |
| `StubInventoryService.reservations` | Active reservation exists |
| `Product.stock_quantity` (H2) | `createOrder()` calls `decrementStock()` — **partially depends on where in the loop the exception occurred** |
| `grocery_order` (H2) | No row (transaction rolled back by `@Transactional`) |
| Payment gateway | **Charged** — money taken |

**Compensation sequence:**

```
Step 5 throws (any exception)
  └─ paymentService.refund(payment.paymentToken())   ← reverse the charge
  └─ inventoryService.releaseReservation(reservation) ← return stock
```

**End state (if compensations succeed):** No order, money refunded, stock restored.

**The dangerous window:** Between the payment charge completing and the refund completing, money is taken with no corresponding order. In the demo this is milliseconds. In production with real payment gateways:

- Refunds can take 3–10 business days to settle on the customer's statement
- The customer sees a charge on their bank app immediately
- The application has no order to reference if the customer calls support
- If the refund call itself fails, the issue requires manual intervention

**Suggested fix:**

Persist the payment token and the "refund pending" state to the outbox **before** attempting the refund. The order can be written with `status = PENDING` first, then updated to `CONFIRMED` after all steps pass, and `CANCELLED` with a refund record on failure. This way:

1. There is always an audit trail linking the payment token to a customer and a cart
2. The outbox worker retries the refund until the gateway confirms
3. Support staff can look up the session and see exactly what happened

---

### Scenario D: Full 3-Way Rollback — Inventory Commit Fails

**Preset:** `inventory-commit-down`, `inventory-commit-flaky`

This is the most complex failure point. By the time `commitReservation` is called, **all three major side effects have already occurred.**

**State at moment of failure:**

| Data store | State |
|------------|-------|
| `StubInventoryService.available` | Already decremented from Step 3 |
| `StubInventoryService.reservations` | Reservation still exists (commit failed before removing it) |
| `Product.stock_quantity` (H2) | Decremented by `createOrder()` at Step 5 |
| `grocery_order` (H2) | Row exists with `status = CONFIRMED` |
| Payment gateway | Charged |

**Compensation sequence:**

```
Step 6 (commitReservation) throws
  └─ 1. paymentService.refund(payment.paymentToken())
         └─ reverses the charge on the gateway
  └─ 2. orderService.cancelOrder(order)
         └─ sets order.status = CANCELLED in H2
         └─ does NOT restore Product.stock_quantity ← see Known Inconsistency below
  └─ 3. inventoryService.releaseReservation(reservation)
         └─ removes reservation record
         └─ restores quantities to available map
```

**End state (if all compensations succeed):**

| Data store | End state |
|------------|-----------|
| `StubInventoryService.available` | Restored ✓ |
| `StubInventoryService.reservations` | Empty ✓ |
| `Product.stock_quantity` (H2) | Still decremented ✗ (display inconsistency — see below) |
| `grocery_order` (H2) | Row exists, `status = CANCELLED` ✓ |
| Payment gateway | Refunded ✓ |

**What the user sees:** "Checkout failed — payment refunded." The saga trace shows all three compensations.

**The compensation ordering matters.** If the compensations were reversed:

- Release reservation first, then refund: another customer could buy the same stock in the window between release and refund. If the refund then fails, the original customer is charged and another customer has the goods.
- Cancel order first, then refund: the order is CANCELLED but the customer is still charged — confusing state visible in the DB before refund completes.

The current order (refund → cancel → release) is safest: money is cleared first, then administrative records, then stock returned to the pool.

---

### Scenario E: Double Submission — Silent Double Charge

**Preset:** `payment-no-idempotency`

**How to reproduce:**
1. Apply the `payment-no-idempotency` preset
2. Add items to cart, go to checkout, submit the form with card `4111 1111 1111 1111`
3. When the success page loads, click the browser **Back** button
4. Submit the checkout form again

**What happens with idempotency OFF:**

Each form submission is treated as a completely new payment request. The UUID embedded in the form is the same (it was generated on the original page load), but the payment stub ignores it when idempotency is disabled.

| Submission | Saga result | Payment log |
|-----------|------------|-------------|
| First | Steps 1–6 all OK — Order #N created, status CONFIRMED | Token: `pay-abc-001` — charged |
| Second (Back + resubmit) | Steps 1–6 all OK — Order #N+1 created, status CONFIRMED | Token: `pay-abc-002` — charged again |

**Why this is a silent bug:** Both submissions complete successfully. No exception is thrown, no compensation fires. The customer has two separate orders and two separate charges. The only evidence:

- Two `CONFIRMED` order rows in the `grocery_order` table with the same session's items
- Two entries in the payment stub's internal log
- The product `stock_quantity` decremented twice

**With idempotency ON (default):** The payment stub sees the same UUID on the second submission, returns the cached result from the first charge, and `CheckoutService` detects the duplicate and skips creating a second order. One charge, one order.

**Suggested fix:**

- Always generate a fresh idempotency key on each checkout form load (UUID as a hidden field)
- Store the key alongside the pending order in the outbox
- Real payment gateways (Stripe, Adyen) deduplicate by `Idempotency-Key` header within a time window (typically 24 hours)
- Add a unique constraint on `(session_id, idempotency_key)` in the `grocery_order` table to make the database reject duplicate submissions at the persistence layer as a second line of defence

---

### Scenario F: Compensation Chain Breaks Mid-Way

This scenario is not directly injectable in the demo (`releaseReservation` is intentionally never fault-injected), but it is the most important failure mode in real systems.

**Example:** Step 6 fails, compensation 1 (refund) succeeds, compensation 2 (cancelOrder) fails mid-execution.

**Resulting state:**

| Data store | State |
|------------|-------|
| Payment gateway | Refunded ✓ |
| `grocery_order` (H2) | `status = CONFIRMED` — money refunded but order looks active ✗ |
| `StubInventoryService.available` | Units still held ✗ |

This is the inconsistent state the Saga pattern is designed to eventually resolve, but without an outbox the compensation is lost if the JVM crashes here.

**In this demo:** The compensation methods are called synchronously in a try/catch chain. If `cancelOrder()` throws, `releaseReservation()` is never called. The exception propagates and the caller gets an error, but there is no retry mechanism.

**Suggested fix — Outbox table:**

```
On Step 6 failure, before any compensation runs:
  INSERT INTO outbox (event_type, payload, status, attempts)
  VALUES
    ('REFUND',              '{"token": "pay-abc-001"}',     'PENDING', 0),
    ('CANCEL_ORDER',        '{"order_id": 42}',             'PENDING', 0),
    ('RELEASE_RESERVATION', '{"reservation_id": "res-xyz"}', 'PENDING', 0)
  -- all in one ACID transaction

Background worker (polling every N seconds):
  SELECT * FROM outbox WHERE status = 'PENDING' ORDER BY id
  FOR EACH event:
    execute the action
    if success: UPDATE status = 'DONE'
    if failure: UPDATE attempts = attempts + 1
                if attempts > MAX_RETRIES: UPDATE status = 'DEAD_LETTER'
```

The outbox survives JVM crashes. On restart, the worker picks up `PENDING` events. Dead-letter events trigger a PagerDuty alert for manual review.

---

### Scenario G: Silent Discount Loss

**Preset:** `promotion-down-graceful`, `promotion-flaky` + graceful, `coupon-down`, `coupon-flaky`

These scenarios are silent from the saga's perspective — no exception propagates, no rollback occurs — but the customer pays more than the advertised price.

**Promotion service down (graceful):**

The checkout saga logs a `DEGRADED` step and continues with `promotion.discount() = $0.00`. The customer entered a valid promo code and expects a 10% discount but pays full price. The saga trace on the error page would show this, but on a success page there is no trace shown.

**Coupon engine down:**

Cart mutations return `CouponResult.none()`. The cart page shows the "⚡ Engine DOWN" badge so the user can see it, but nothing stops them from checking out. The `finalTotal` in `CheckoutService` subtracts `cart.getCouponDiscount()` which is `$0.00` when the engine is down — the customer pays full price.

**Detection:**

- Graceful degradation should always emit a metric: `promo_service.degraded_count` or similar
- Compare `order.discount` in the DB against the discount the customer was shown at cart review time — a mismatch with no corresponding saga failure indicates silent loss
- Alert if `promo_service.degraded_count` exceeds a threshold in any rolling window

**Suggested fix:**

1. Always alert on graceful degradation — customers are affected even if the system doesn't crash
2. Capture the *expected* discount in the session at cart-review time; if checkout applies a lower discount, show a notice ("Promo code could not be applied — you've been charged full price")
3. Consider a post-checkout reconciliation job that identifies orders where a valid promo code existed but no discount was recorded, and automatically issues a credit

---

### Known Data Inconsistency After Rollback

This is a real limitation in the current application, observable via the H2 console after triggering `inventory-commit-down`.

**Root cause:**

`OrderService.createOrder()` calls `productRepository.decrementStock()` for each item inside a `@Transactional` method. This decrements `Product.stock_quantity` in H2 — the display cache.

`OrderService.cancelOrder()` — called during compensation — only sets `order.status = CANCELLED`. **It does not restore `Product.stock_quantity`.**

`inventoryService.releaseReservation()` restores `StubInventoryService.available` (the authoritative counter) but does not touch the H2 product table.

**Result after a Step 6 failure and full rollback:**

```
Before checkout:   Product.stock_quantity = 10,  StubInventory.available = 10
After reserveStock: (stock_quantity unchanged)    StubInventory.available = 8
After createOrder:  stock_quantity = 8            StubInventory.available = 8
After rollback:     stock_quantity = 8  ← WRONG   StubInventory.available = 10 ← correct
```

**Observable effect:**
- Product listing page shows "8 in stock" even though 10 are available
- Checkout (which uses `StubInventory.available`) allows buying up to 10 units
- The display and the reality are out of sync until the next server restart (which re-seeds from DB — making the DB the new wrong baseline)

**Suggested fix:**

Two options:

1. **Restore the display cache in `cancelOrder()`**: add a `productRepository.incrementStock(productId, quantity)` call for each item. This keeps DB and stub in sync but requires `cancelOrder` to know the item quantities.

2. **Separate the display stock from the reservation stock entirely**: the product listing page queries `inventoryService.getStock(productId)` (the authoritative value) rather than `product.stock_quantity`. The DB column becomes write-only metadata rather than a source of truth for the UI.

Option 2 is architecturally cleaner: there is one authoritative source of truth for stock (the inventory service) and the UI always reads from it. The H2 column becomes an audit field — it records the quantity at time of confirmed order, not a live display value.

---

## Design Patterns Illustrated

### 1. Saga Pattern (Compensating Transactions)

A distributed checkout cannot be wrapped in a single ACID transaction — payment and inventory live in separate systems with no shared transaction coordinator. The saga pattern sequences local operations and defines a compensating action for each step with side effects, executed in reverse order on failure.

**Trade-off:** compensations are not atomic — a crash mid-compensation leaves the system in a partial state. This is why the outbox table (write-ahead log for compensations) is essential in production.

### 2. Graceful Degradation

Not all services are equal. The key question for each dependency: *"Can the core business transaction succeed without this service?"*

- **Inventory**: Critical — cannot sell what you don't have
- **Payment**: Critical — cannot fulfil orders without receiving payment
- **Promotion service**: Non-critical — a customer can buy at full price
- **Coupon engine**: Non-critical — cart discounts are a convenience, not a blocker

Non-critical dependencies degrade gracefully: failures are caught, logged, and the operation continues with reduced functionality. Always emit a metric when degrading — silent degradation hides outages.

### 3. Timeout Isolation

Payment runs on a dedicated daemon thread with `Future.get(paymentTimeoutMs)`. If it exceeds the deadline, the future is cancelled and compensation fires. This prevents a slow payment gateway from holding a servlet thread indefinitely.

No other service in this application has this protection — intentionally. The *Inventory SLOW at reserve* preset demonstrates what happens when inventory runs on the servlet thread without a timeout: the browser spinner runs for 4 seconds, the thread is held the entire time, and under load this exhausts the thread pool and queues or rejects all other requests.

**The deliberate asymmetry is the lesson:** apply timeouts to every external call, not just the most obvious one.

### 4. Idempotency

The checkout form embeds a UUID as a hidden field on page load. The payment service caches results keyed by this UUID. A retry — whether from a network glitch, a slow response that completed after the client gave up, or a browser Back-button resubmit — replays the original result rather than issuing a second charge.

When idempotency is disabled, the same form resubmission creates a duplicate charge with no saga error. The bug is silent: both charges succeed, both look normal in the payment log, and the only evidence is that the customer was charged twice.

### 5. Intermittent Failures (FLAKY mode)

FLAKY mode fails every other call, simulating unstable network partitions or a host cycling through restarts. The key distinction:

- **Read-only operations** (promotion lookup, availability check): safe to retry immediately — no state was changed
- **Write operations** (payment): retry only if an idempotency key is in place — otherwise a "failed" call that actually succeeded will be charged again on retry

FLAKY + graceful degradation on the promotion service is the safest combination: every other checkout skips the discount, but no data is corrupted and no compensation is needed.

---

## Preset Quick Reference

| Preset | Service | Mode | Saga steps affected | Compensation |
|--------|---------|------|--------------------|-----------__|
| `all-normal` | — | Reset | — | — |
| `payment-down` | Payment | DOWN | 4 fails | releaseReservation |
| `payment-slow` | Payment | SLOW (2s < 3s timeout) | 4 slow, succeeds | None |
| `payment-timeout` | Payment | SLOW (5s > 3s timeout) | 4 times out | releaseReservation |
| `payment-flaky` | Payment | FLAKY | 4 alternates | releaseReservation on even calls |
| `payment-no-idempotency` | Payment | — (toggle off) | Silent double-charge on resubmit | None (bug is silent) |
| `inventory-check-down` | Inventory | DOWN at step 1 | 1 fails | None |
| `inventory-check-flaky` | Inventory | FLAKY at step 1 | 1 alternates | None |
| `inventory-check-slow` | Inventory | SLOW (3s) at step 1 | 1 blocks thread | None |
| `inventory-reserve-down` | Inventory | DOWN at step 3 | 3 fails | None (payment not attempted) |
| `inventory-reserve-flaky` | Inventory | FLAKY at step 3 | 3 alternates | None on failure |
| `inventory-reserve-slow` | Inventory | SLOW (4s) at step 3 | 3 blocks thread | None |
| `inventory-commit-down` | Inventory | DOWN at step 6 | 6 fails | refund + cancelOrder + releaseReservation |
| `inventory-commit-slow` | Inventory | SLOW (3s) at step 6 | 6 blocks thread | None (succeeds after delay) |
| `inventory-commit-flaky` | Inventory | FLAKY at step 6 | 6 alternates | Full 3-way on even calls |
| `promotion-slow` | Promotion | SLOW (3s) + graceful | 2 blocks thread | None |
| `promotion-down-graceful` | Promotion | DOWN + graceful | 2 DEGRADED, continues | None |
| `promotion-down-hard` | Promotion | DOWN + hard fail | 2 fails, abort | None (before step 3) |
| `promotion-flaky` | Promotion | FLAKY + graceful | 2 alternates | None |
| `coupon-down` | Coupon engine | DOWN | Cart: no offers. ⚡ badge shown | None (cart still works) |
| `coupon-flaky` | Coupon engine | FLAKY | Cart: offers alternate per load | None |
| `coupon-slow` | Coupon engine | SLOW (3s) | Cart mutations block thread 3s | None |

---

## What a Production System Would Add

| Gap in this demo | Production solution |
|-----------------|---------------------|
| Compensations are fire-and-forget | **Outbox table** — write compensating events to DB in same transaction; background worker retries with exponential back-off until confirmed |
| Only payment has a timeout | **`@TimeLimiter`** on every external call; move blocking calls off the servlet thread |
| No circuit breaking | **`@CircuitBreaker`** (Resilience4j) — opens after N consecutive failures, fails fast without blocking, half-opens periodically to probe recovery |
| No retry logic | **`@Retry`** (Resilience4j) with exponential back-off and jitter; only retry idempotent operations or operations with idempotency keys |
| Idempotency key in memory only | Store key in outbox alongside pending order; replay same key on every retry attempt |
| No observability | **Distributed tracing** (OpenTelemetry) — propagate trace IDs across all service calls so a failed saga can be reconstructed end-to-end in Grafana or Jaeger |
| Silent degradation | Emit a metric and fire an alert whenever graceful degradation activates |
| FLAKY counter resets on `all-normal` | In production, intermittent failures are not deterministic — use random failure probability with configurable rate |
| Coupon engine blocks cart mutations | Evaluate offers asynchronously; push updates via SSE or polling to avoid blocking cart interactions |
