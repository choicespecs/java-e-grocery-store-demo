# Design Decisions

This document records the architectural and implementation choices made in the Fresh Groceries demo, the reasoning behind each, the trade-offs accepted, and what a production system would do differently. It also identifies known technical debt items.

---

## Contents

1. [Core Design Goals](#core-design-goals)
2. [Decision Log](#decision-log)
   - [D1: CheckoutService is not @Transactional](#d1-checkoutservice-is-not-transactional)
   - [D2: Payment Runs on a Dedicated Thread](#d2-payment-runs-on-a-dedicated-thread)
   - [D3: Other External Calls Run on the Servlet Thread](#d3-other-external-calls-run-on-the-servlet-thread)
   - [D4: Inventory Has Three Independent Fault Points](#d4-inventory-has-three-independent-fault-points)
   - [D5: CartPromotionService Is Never Fault-Injected](#d5-cartpromotionservice-is-never-fault-injected)
   - [D6: releaseReservation Is Never Fault-Injected](#d6-releasereservation-is-never-fault-injected)
   - [D7: Promotion Service Supports Graceful Degradation via a Toggle](#d7-promotion-service-supports-graceful-degradation-via-a-toggle)
   - [D8: Idempotency Key Is Generated Per Form Load](#d8-idempotency-key-is-generated-per-form-load)
   - [D9: Two Sources of Truth for Stock](#d9-two-sources-of-truth-for-stock)
   - [D10: OrderItem Uses a Data Snapshot, Not a Foreign Key](#d10-orderitem-uses-a-data-snapshot-not-a-foreign-key)
   - [D11: Cart Is @SessionScope, Not a DB Entity](#d11-cart-is-sessionscope-not-a-db-entity)
   - [D12: Stubs Are the Default @Service Implementations](#d12-stubs-are-the-default-service-implementations)
   - [D13: DemoFaultConfig Uses volatile Fields](#d13-demofaultconfig-uses-volatile-fields)
   - [D14: SagaTrace Is Per-Request, Not Persistent](#d14-sagatrace-is-per-request-not-persistent)
   - [D15: cancelOrder Does Not Restore Product.stockQuantity](#d15-cancelorder-does-not-restore-productstockquantity)
3. [Patterns Implemented](#patterns-implemented)
4. [Known Technical Debt](#known-technical-debt)
5. [What a Production System Would Add](#what-a-production-system-would-add)

---

## Core Design Goals

This project is an educational demonstration, not a production system. Its design is shaped by three priorities:

1. **Make distributed systems failure modes observable** — every failure path should be triggerable with one click and visible in the UI without reading source code.
2. **Use real patterns correctly** — the saga, idempotency, graceful degradation, and thread isolation are implemented as they would be in production code, not as simplified mocks.
3. **Make the wrong paths equally visible** — the application intentionally ships with gaps (no circuit breakers, only payment has a timeout, `cancelOrder` does not restore stock) so students can observe what happens when those protections are absent.

---

## Decision Log

### D1: CheckoutService is not @Transactional

**Decision:** `CheckoutService` carries no `@Transactional` annotation. Individual DB operations in `OrderService` carry their own.

**Reasoning:** The saga spans three external services (inventory, payment, promotion) and one database. External service calls cannot participate in a JDBC transaction. If `CheckoutService` were `@Transactional`, Spring would open a connection from the pool at the start of the method and hold it through all six steps, including the payment call which can block for several seconds. This would exhaust the connection pool under load without providing any of the atomicity guarantees `@Transactional` normally offers for cross-system operations.

**Trade-off accepted:** Atomicity is replaced by the saga pattern — each step defines a compensating action that is applied if a later step fails. Compensations are not atomic: a JVM crash mid-compensation leaves the system in a partial state. See D6 for the production mitigation.

---

### D2: Payment Runs on a Dedicated Thread

**Decision:** `CheckoutService` submits the payment call to a `CachedThreadPool` (daemon threads named `payment-call`) and waits on `paymentFuture.get(paymentTimeoutMs, MILLISECONDS)`.

**Reasoning:** Payment is the call most likely to be slow or to hang. If it runs on the servlet request thread, a slow gateway holds a Tomcat thread for the duration. With a default pool of 200 threads and a 30-second gateway timeout, 200 concurrent hung payments would saturate the thread pool and make the entire application unresponsive — including all cart browsing, which has nothing to do with payment.

The dedicated thread isolates the risk: the servlet thread waits for at most `paymentTimeoutMs` (default 3 s), then cancels the future, runs the compensation, and returns an error to the user. The payment thread may still complete in the background, but it does not hold a servlet thread.

**Trade-off accepted:** The timeout introduces an ambiguous state window. If payment completes on the gateway side just after `TimeoutException` fires, the card is charged but the application does not know. The idempotency key (D8) mitigates this on retry, but the original attempt has no recovery path without a payment reconciliation job. See [CHAOS_ENGINEERING.md](CHAOS_ENGINEERING.md#scenario-b-payment-timeout--the-ambiguous-case).

---

### D3: Other External Calls Run on the Servlet Thread

**Decision:** Inventory (`checkAvailability`, `reserveStock`, `commitReservation`), promotion, and coupon calls all run directly on the servlet request thread with no timeout protection.

**Reasoning:** This is a deliberate teaching gap. The *Inventory SLOW at reserve* preset (4 s delay) demonstrates exactly what happens: the browser spins for 4 seconds, the servlet thread is held the entire time, and under concurrent load this exhausts the thread pool. The asymmetry with payment (which is protected) drives the lesson that timeouts belong on every external call.

**Production fix:** Wrap each external call in a `CompletableFuture.orTimeout()` or Resilience4j `@TimeLimiter`. Move blocking calls to a dedicated executor with bounded queue size.

---

### D4: Inventory Has Three Independent Fault Points

**Decision:** `StubInventoryService` reads three separate `FaultMode` fields from `DemoFaultConfig`: one for `checkAvailability`, one for `reserveStock`, and one for `commitReservation`. All three share one `AtomicInteger` flaky counter.

**Reasoning:** The three inventory steps occupy different positions in the saga and carry different risk profiles. Failing at Step 1 (read-only pre-flight) has zero side effects. Failing at Step 3 (after stock is reserved) means payment was never attempted. Failing at Step 6 (after payment and order persist) requires a three-way compensation. Combining all three into one fault point would make it impossible to isolate and observe these distinct cases.

**Trade-off accepted:** Sharing one flaky counter across all three inventory fault points means the counter advances on every inventory call regardless of which step fires. This is acceptable for a demo but would be confusing in a system with independent flaky rates per operation.

---

### D5: CartPromotionService Is Never Fault-Injected

**Decision:** `StubCartPromotionService` does not call `FaultInjector.apply()`. The `getAvailableDeals()` catalog and the `evaluate()` discount calculation always succeed.

**Reasoning:** Cart promotions (Buy 2 Get 1, quantity discounts) represent the simplest discount tier and are the baseline expected by users. They run on every cart mutation. Making them fault-injectable would conflate two conceptually separate things: the fragility of the coupon engine (which is an external service) and the correctness of automatic cart rules (which are local business logic). The separation also gives the coupon engine DOWN preset a clean "still see deals, coupon offers unavailable" user experience.

---

### D6: releaseReservation Is Never Fault-Injected

**Decision:** `StubInventoryService.releaseReservation()` has no call to `FaultInjector.apply()`. It always executes.

**Reasoning:** `releaseReservation` is only ever called as a compensating action. If compensations can fail, the saga loses its ability to restore consistent state. A failed payment followed by a failed release leaves stock permanently reserved — units appear unavailable even though no order exists and no money was charged. In a demo where this is observable via the H2 console, an unjustifiable fault here would confuse rather than educate.

**Production note:** In reality, `releaseReservation` calls a real external service and can fail. The production mitigation is an outbox table: write the compensating intent to the database in the same ACID transaction as the failed step, then retry asynchronously until the service confirms. See [CHAOS_ENGINEERING.md](CHAOS_ENGINEERING.md#scenario-f-compensation-chain-breaks-mid-way).

---

### D7: Promotion Service Supports Graceful Degradation via a Toggle

**Decision:** `DemoFaultConfig` carries a `promotionGracefulDegradation` boolean. When `true` and `PromotionService` throws `ServiceUnavailableException`, `CheckoutService` logs a `DEGRADED` step and continues with zero promo discount. When `false`, the exception propagates and checkout aborts.

**Reasoning:** This is the canonical "is this dependency on the critical path?" question. The promo service is demonstrably non-critical — an order without a discount code is still a valid order. The toggle forces a concrete choice at the code level: graceful degradation is not automatic, it is an explicit architectural decision that requires both a code path and an operational commitment (emit a metric so the outage is visible).

**Trade-off accepted:** Graceful degradation can cause silent revenue impact — customers who entered a valid promo code pay full price without knowing it. See [CHAOS_ENGINEERING.md](CHAOS_ENGINEERING.md#scenario-g-silent-discount-loss) for the detection and remediation discussion.

---

### D8: Idempotency Key Is Generated Per Form Load

**Decision:** `CheckoutController.checkoutForm()` generates a UUID and sets it on the `CheckoutRequest` as a hidden form field. `StubPaymentService` caches results by this key in a `ConcurrentHashMap`. A retry or back-button resubmit with the same key returns the cached result without a second charge.

**Reasoning:** HTTP POST forms are not safe to retry — the browser warns users about resubmission, but the warning is easy to dismiss. The idempotency key makes the payment call safe to replay. A new UUID on each form load (not on each form submit) means a genuine new checkout attempt gets a fresh key while a Back-and-resubmit replays the original.

**Trade-off accepted:** The idempotency cache is in-process and lost on restart. It also does not protect against concurrent submissions of the same form from two browser tabs — both would have the same UUID and the second submission would return the first result, which is the correct behaviour but could confuse users. In production, the key is stored in the outbox alongside the pending order so it survives restarts and is replayable across the system boundary.

---

### D9: Two Sources of Truth for Stock

**Decision:** Stock quantity lives in two places: `StubInventoryService.available` (authoritative, in-memory ConcurrentHashMap) and `Product.stockQuantity` (H2 column, display cache). They are kept in sync by seeding on startup and decrementing after confirmed orders, but diverge after a rollback.

**Reasoning:** Checkout correctness requires the authoritative counter to be updated atomically at reservation time, not at order confirmation time. If the display counter were authoritative, two concurrent checkouts could both see "5 in stock" and both succeed, overselling by the reservation window duration. The two-phase reserve/commit sequence on the in-memory counter prevents this. The H2 column is a convenient display value that is accurate enough for the product listing without requiring a call to the inventory service on every page load.

**Known limitation:** After a Step 6 rollback, `available` is restored by `releaseReservation` but `Product.stockQuantity` is not restored by `cancelOrder`. The display stock and the authoritative stock diverge until restart. This is documented as a known inconsistency and discussed in detail in [CHAOS_ENGINEERING.md](CHAOS_ENGINEERING.md#known-data-inconsistency-after-rollback).

**Production fix:** Either (a) restore `Product.stockQuantity` in `cancelOrder`, or (b) have the product listing query `inventoryService.getStock(productId)` rather than `product.stockQuantity`, making the inventory service the single source of truth for the UI.

---

### D10: OrderItem Uses a Data Snapshot, Not a Foreign Key

**Decision:** `OrderItem` stores `productId` (plain `BIGINT` column, not a `@ManyToOne` FK), `productName` (VARCHAR), and `unitPrice` (DECIMAL) as immutable snapshots of what the customer purchased.

**Reasoning:** Orders must remain accurate even if a product is later renamed, repriced, or deleted. A foreign key to the product table would cause `JOIN` results to drift as the product catalogue changes. The snapshot approach also matches how real payment processors and ERP systems store order line items — the receipt reflects what was bought, not the current state of the catalogue.

**Trade-off accepted:** There is no referential integrity on `product_id` in the order table. A query to find all orders containing a product requires joining on the plain integer column, which is not indexed in this demo. For a production system with large order volumes, adding an index on `order_item.product_id` would be necessary.

---

### D11: Cart Is @SessionScope, Not a DB Entity

**Decision:** `Cart` is a `@SessionScope @Component` backed by the HTTP session. It is not persisted to the database.

**Reasoning:** For a demo application with no user accounts, session storage is the simplest correct approach. Cart items are price-locked snapshots (`CartItem` stores `unitPrice` at add-time), preventing price drift during a browsing session. The session-scoped bean is injected into controllers as a CGLIB proxy; services receive it as a method parameter so no singleton bean holds a reference to session state.

**Trade-off accepted:** The cart is lost when the session expires or when the server restarts. There is no "saved cart" or "resume shopping" functionality. In production, carts would be persisted to Redis or a DB table and associated with a user account or an anonymous session token so they survive restarts and are accessible across devices.

---

### D12: Stubs Are the Default @Service Implementations

**Decision:** Each external service interface has exactly one `@Service` implementation in the `stub/` subdirectory. No profile annotation is required for the stubs to be active.

**Reasoning:** This makes the project immediately runnable with zero configuration — `./mvnw spring-boot:run` produces a fully functional application with no external dependencies. The trade-off is that accidentally deploying the stubs to production would silently simulate payments rather than charging real cards.

**Production convention:** Annotate real implementations `@Service @Profile("prod")` and stub implementations `@Profile("!prod")`. This makes the stubs explicit development-only beans and prevents accidental deployment.

---

### D13: DemoFaultConfig Uses volatile Fields

**Decision:** All fault settings in `DemoFaultConfig` are `volatile` primitive or reference fields. No `synchronized` blocks or lock objects are used.

**Reasoning:** `DemoFaultConfig` is read by stub service methods on every checkout attempt (potentially on multiple threads concurrently) and written by `DemoController` on each panel submit. `volatile` guarantees visibility — a write by one thread is immediately visible to all readers — without the overhead of `synchronized`. For a toggle that changes infrequently and where reading a momentarily stale value is acceptable (the next checkout attempt will see the new value), `volatile` is appropriate.

**Limitation:** `volatile` does not provide atomicity for compound operations. `resetAll()` performs multiple sequential field writes that are individually visible but not collectively atomic — a concurrent checkout could observe a partially-reset configuration. This is acceptable in a demo; in production, a `ReentrantReadWriteLock` or an immutable configuration record swapped with `AtomicReference` would be correct.

---

### D14: SagaTrace Is Per-Request, Not Persistent

**Decision:** `SagaTrace` is instantiated in `CheckoutController.processCheckout()` and passed through the call stack. It is added to the Thymeleaf model on failure. It is not stored anywhere after the request completes.

**Reasoning:** The trace's purpose is immediate feedback — showing the developer or student what happened in this checkout attempt. Persisting traces to a database would add complexity with no educational benefit in a demo. On success, the trace is discarded (the confirmation page has no trace to show, which is intentional — a clean success needs no explanation).

**Production equivalent:** Distributed tracing (OpenTelemetry, Micrometer Tracing) propagates a trace ID across all service calls in a saga. The trace is recorded in a backend (Jaeger, Grafana Tempo) and is queryable after the fact, including for successful requests.

---

### D15: cancelOrder Does Not Restore Product.stockQuantity

**Decision:** `OrderService.cancelOrder()` sets `order.status = CANCELLED` but does not call `productRepository.incrementStock()`.

**Reasoning:** This is an intentional teaching gap rather than an oversight. The inconsistency (authoritative stock restored, display cache not restored) is observable in the H2 console after triggering `inventory-commit-down`. It demonstrates that compensation logic must be comprehensive — cancelling an order is not the same as reversing all of its side effects.

**Production fix:** Either restore the display cache in `cancelOrder`, or stop using `Product.stockQuantity` as a live display value and query the inventory service directly. See [CHAOS_ENGINEERING.md](CHAOS_ENGINEERING.md#known-data-inconsistency-after-rollback).

---

## Patterns Implemented

### Saga Pattern (Compensating Transactions)

`CheckoutService` implements a choreography-free orchestrated saga. One orchestrator (`CheckoutService`) calls each participant in sequence, handles failures, and dispatches compensations. There is no saga coordinator bus or event log — compensations are called synchronously in `catch` blocks.

The pattern is correct for the happy path and for single-failure scenarios. It is fragile when compensations themselves fail (see D6 and [Scenario F](CHAOS_ENGINEERING.md#scenario-f-compensation-chain-breaks-mid-way)).

### Graceful Degradation

The promotion service implements the non-critical dependency pattern: a toggle controls whether unavailability is fatal or silently absorbed. The coupon engine always degrades gracefully — it is treated as an enhancement, not a blocker.

### Timeout Isolation (Bulkhead)

The payment executor is a bulkhead: it isolates the servlet request thread from the payment service's response time. Without it, a slow gateway would exhaust the servlet thread pool. The payment executor is a `CachedThreadPool` (unbounded — a production system would use a `ThreadPoolExecutor` with bounded queue size to prevent unlimited thread creation).

### Idempotency

The checkout form embeds a UUID as a hidden field (generated once per form load). The payment service deduplicates by this key. This is the standard approach used by real payment gateways (Stripe `Idempotency-Key` header, Adyen `reference` field).

### Two-Phase Commit (Simplified)

Inventory uses a two-phase reserve/commit sequence. `reserveStock` (Step 3) reduces availability immediately; `commitReservation` (Step 6) makes the reduction permanent by removing the reservation record. Between these two steps, the units are "held" — invisible to new checkouts but not yet permanently consumed. On any failure between Steps 3 and 6, `releaseReservation` returns the held units to the available pool.

---

## Known Technical Debt

| ID | Location | Description | Severity |
|---|---|---|---|
| TD1 | `CheckoutService` | No timeout on inventory calls (`reserveStock`, `commitReservation`). A slow inventory service blocks the servlet thread with no escape. | High — thread exhaustion risk under load |
| TD2 | `CheckoutService` | No timeout on promotion service call. Same thread exhaustion risk as TD1 but lower probability (promo is skipped if no code is entered). | Medium |
| TD3 | `CheckoutService` | No retry logic on any step. Transient failures require the user to manually resubmit. | Medium |
| TD4 | `CheckoutService` | No circuit breaker. After N consecutive failures, all subsequent calls still attempt the service rather than failing fast. | High — amplifies thread exhaustion |
| TD5 | `OrderService.cancelOrder` | Does not restore `Product.stockQuantity`, causing display inconsistency after rollback. | Low — display only, not functionally incorrect |
| TD6 | `StubPaymentService` | Idempotency cache is unbounded in-memory with no TTL. Under sustained load, the map grows indefinitely. | Low — demo only, never deployed |
| TD7 | `DemoFaultConfig.resetAll` | Multiple sequential `volatile` writes are not collectively atomic. A concurrent checkout can observe a partially-reset configuration. | Low — acceptable for a demo toggle |
| TD8 | Payment executor | `CachedThreadPool` is unbounded. A flood of slow payment calls creates unlimited threads. | Low in demo (short-lived); High in production. |
| TD9 | All stubs | No distributed tracing. A failed saga cannot be reconstructed from logs after the fact — only the live `SagaTrace` in the browser provides visibility. | Medium — operational gap |
| TD10 | `StubInventoryService` | All three inventory fault points share one `AtomicInteger` flaky counter. Counter advances on every inventory call, not just the faulted step. | Low — cosmetic in demo |

---

## What a Production System Would Add

| Gap | Production solution |
|---|---|
| Compensations are fire-and-forget synchronous calls | Outbox table — write compensating events to the DB in the same ACID transaction as the failed step; a background worker retries with exponential back-off until each succeeds. JVM crashes cannot lose events. |
| Only payment has a timeout | `CompletableFuture.orTimeout()` or Resilience4j `@TimeLimiter` on every external call. Move blocking calls off the servlet thread to dedicated executors with bounded queues. |
| No circuit breaking | Resilience4j `@CircuitBreaker` — opens after N consecutive failures, fails fast without blocking threads, half-opens periodically to probe recovery. |
| No retry with back-off | Resilience4j `@Retry` with exponential back-off and jitter; only annotate idempotent methods or methods with idempotency keys. |
| No distributed tracing | OpenTelemetry / Micrometer Tracing — propagate trace IDs across all service calls so a failed saga is reconstructable end-to-end in Grafana or Jaeger. |
| In-memory idempotency cache | Store the idempotency key in the outbox alongside the pending order so replay survives JVM restarts. |
| Silent graceful degradation | Emit a counter metric on every graceful degradation event; alert if the count exceeds a threshold in any rolling window. |
| No dead-letter handling | Compensating actions that fail after N retries land in a dead-letter queue for manual review rather than being silently dropped. |
| Cart in HTTP session only | Persist carts to Redis or a dedicated table, associated with a user account or anonymous session token, so carts survive restarts and are accessible across devices. |
| H2 in-memory DB | PostgreSQL or MySQL with connection pool (HikariCP default in Spring Boot), migration management (Flyway or Liquibase), and read replicas for catalogue queries. |
