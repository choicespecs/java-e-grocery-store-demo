# Fresh Groceries — E-Grocery Store Demo

A Spring Boot 3 / Java 17 / Thymeleaf monolith that demonstrates distributed systems patterns in a concrete, runnable context. The focus is the **checkout saga**: a sequence of calls across three external services (inventory, payment, promotions) where each step carries explicit compensating actions if a later step fails.

A built-in **Chaos Engineering Panel** lets you break individual services mid-saga and watch which compensations fire, what the user sees, and what design patterns prevent data corruption — with a live step-by-step execution trace on every failed checkout.

---

## Quick Start

```bash
./mvnw spring-boot:run
```

| URL | Description |
|---|---|
| `http://localhost:8080` | Product catalogue |
| `http://localhost:8080/demo` | Chaos Engineering Panel |
| `http://localhost:8080/h2-console` | In-browser SQL console (`jdbc:h2:mem:grocerydb`, user `sa`, blank password) |

```bash
./mvnw test          # run all tests
./mvnw package       # build JAR
```

No external infrastructure required — H2 runs embedded and all external services are in-memory stubs that reset on restart.

---

## Tech Stack

| Layer | Technology |
|---|---|
| Language | Java 17 |
| Framework | Spring Boot 3.4 |
| Web / MVC | Spring MVC + Thymeleaf |
| Persistence | Spring Data JPA + Hibernate |
| Database | H2 (in-memory) |
| Build | Maven (wrapper included) |
| CSS | Bootstrap 5.3 (CDN) |

---

## Architecture

```
controller/          HTTP → Thymeleaf rendering; catches domain exceptions and maps to UI messages
  CartModelAdvice    ControllerAdvice — injects cartItemCount + categories into every template
  DemoController     GET/POST /demo — reads and writes DemoFaultConfig
service/             Business logic
  CheckoutService    Owns the checkout saga; intentionally NOT @Transactional
  OrderService       @Transactional DB writes for order creation and cancellation
external/{name}/     Interface + request/result records per external system
  {name}/stub/       The only @Service implementing each interface — swap here for prod
demo/                Fault injection infrastructure: FaultMode, DemoFaultConfig, FaultInjector, SagaTrace
domain/              JPA entities: Category, Product, Order, OrderItem, OrderStatus
dto/                 Cart (@SessionScope bean) + CartItem (session snapshot)
config/              DataInitializer — seeds 16 products and the inventory stub on startup
exception/           Typed exception per failure mode; caught selectively in controllers
```

### Package tree

```
src/main/java/com/demo/grocery/
├── GroceryApplication.java
├── config/
│   └── DataInitializer.java
├── controller/
│   ├── CartController.java
│   ├── CartModelAdvice.java
│   ├── CheckoutController.java
│   ├── DemoController.java
│   └── ProductController.java
├── demo/
│   ├── DemoFaultConfig.java       ← singleton holding all fault settings
│   ├── FaultInjector.java         ← static helper; throws or sleeps based on mode
│   ├── FaultMode.java             ← NORMAL | DOWN | SLOW | FLAKY
│   └── SagaTrace.java             ← per-request step log shown in UI on failure
├── domain/  (Category, Product, Order, OrderItem, OrderStatus)
├── dto/     (Cart, CartItem, CheckoutRequest)
├── exception/
│   ├── CheckoutException.java
│   ├── InsufficientStockException.java
│   ├── InvalidPromotionException.java
│   ├── PaymentFailedException.java
│   └── ServiceUnavailableException.java
├── external/
│   ├── cartpromotion/
│   │   ├── CartDeal.java
│   │   ├── CartPromotionResult.java
│   │   ├── CartPromotionService.java
│   │   └── stub/StubCartPromotionService.java
│   ├── inventory/
│   │   ├── InventoryReservation.java
│   │   ├── InventoryService.java
│   │   └── stub/StubInventoryService.java
│   ├── payment/
│   │   ├── PaymentRequest.java
│   │   ├── PaymentResult.java
│   │   ├── PaymentService.java
│   │   ├── PaymentStatus.java
│   │   └── stub/StubPaymentService.java
│   └── promotion/
│       ├── PromotionResult.java
│       ├── PromotionService.java
│       └── stub/StubPromotionService.java
├── repository/  (CategoryRepository, OrderRepository, ProductRepository)
└── service/
    ├── CheckoutService.java       ← start reading here
    ├── OrderService.java
    └── ProductService.java
```

---

## The Checkout Saga

`CheckoutService` is the most important class. It coordinates six steps across three external services with explicit compensation at each failure point.

```
Step 1  inventoryService.checkAvailability()    read-only pre-flight, no side effects
Step 2  promotionService.applyPromotion()        read-only; supports graceful degradation
Step 3  inventoryService.reserveStock()          SIDE EFFECT: reduces available stock
Step 4  paymentService.processPayment()          SIDE EFFECT: charges the card
         └─ runs on a dedicated thread with a configurable deadline (paymentTimeoutMs)
Step 5  orderService.createOrder()               DB write (@Transactional)
Step 6  inventoryService.commitReservation()     finalises the hold
```

### Compensation map

| Failure point | Compensations fired |
|---|---|
| Inventory DOWN at Step 1 | None — no side effects yet |
| Inventory SLOW at Step 3 | None needed — succeeds after delay, but blocks servlet thread |
| Inventory DOWN at Step 3 | None — payment not attempted |
| Promotion DOWN (hard-fail) | None — no side effects yet |
| Promotion DOWN (graceful) | Proceeds with zero discount (DEGRADED logged) |
| Promotion FLAKY (graceful) | Proceeds with zero discount on failing calls — read-only, safe to skip |
| Payment DOWN / declined | `releaseReservation` → stock returned to pool |
| Payment TIMEOUT | `releaseReservation` → stock returned; no charge was made |
| DB persist fails (Step 5) | `refund` + `releaseReservation` |
| Inventory commit fails (Step 6) | `refund` + `cancelOrder` + `releaseReservation` |

`CheckoutService` is intentionally **not** `@Transactional` — the saga spans external calls that cannot be wrapped in a single DB transaction. Each individual DB write carries its own `@Transactional`.

---

## External Services

Each external service follows the same structure so real implementations can be swapped in without touching business logic:

```
external/payment/
  PaymentService.java           ← interface (the contract)
  PaymentRequest.java           ← request record
  PaymentResult.java            ← response record
  stub/StubPaymentService.java  ← @Service implementation (in-memory)
```

Spring auto-wires the stub because it is the only `@Service` implementing the interface. To swap in a real implementation, annotate it `@Service @Profile("prod")` and the stub `@Profile("!prod")`.

### Inventory (`InventoryService`)

Two-phase reserve/commit prevents overselling under concurrent sessions.

| Operation | Side effect | Notes |
|---|---|---|
| `checkAvailability` | None | Read-only pre-flight |
| `reserveStock` | Deducts from available pool | Holds stock for this order |
| `commitReservation` | Removes the hold | Makes deduction permanent |
| `releaseReservation` | Returns held stock | Never fault-injected — compensations must be reliable |

**Two sources of truth for stock:**

| Source | Role |
|---|---|
| `Product.stockQuantity` (H2 DB) | Display value on product pages; decremented only after a confirmed order |
| `StubInventoryService.available` (ConcurrentHashMap) | Authoritative for checkout; reserve/release happen here in real-time |

### Payment (`PaymentService`)

**Test card numbers:**

| Card | Outcome |
|---|---|
| `4111 1111 1111 1111` | Success |
| `4000 0000 0000 0002` | Declined — generic |
| `4000 0000 0000 9995` | Declined — insufficient funds |
| `4000 0000 0000 0127` | Declined — incorrect CVC |

Use any future expiry (e.g. `12/2099`) and any 3-digit CVV.

**Idempotency:** a UUID is generated once per checkout form load and embedded as a hidden field. The stub caches results by this key — a network retry or accidental double-submit replays the original result rather than double-charging. Disable the toggle on the demo panel to observe the difference.

**Timeout:** the payment call runs on a daemon-thread executor. If it exceeds `paymentTimeoutMs` (default 3 s), the `Future` is cancelled, the inventory reservation is released, and the user sees a clear error with rollback confirmation.

### Promotion / Promo Code (`PromotionService`)

Applied at checkout by entering a code. Invalid codes throw `InvalidPromotionException`, which the controller catches separately from other errors to render an inline form message.

| Code | Discount |
|---|---|
| `SAVE10` | 10% off |
| `SAVE20` | 20% off |
| `FLAT5` | $5.00 off |
| `WELCOME` | 15% off |

**Graceful degradation:** the promotion service is non-critical. When it is unavailable, `CheckoutService` can either proceed without a discount (degradation ON) or abort the checkout entirely (degradation OFF). Toggle this on the demo panel to compare both designs.

### Cart Promotions (`CartPromotionService`)

Automatic quantity-based deals — no code required. Re-evaluated on every cart mutation (add, update, remove). Discounts are applied before promo codes and before checkout.

| Product | Deal |
|---|---|
| Whole Milk (1 gal) | Buy 2, Get 1 Free — every 3rd unit is free |
| Organic Apples (1 lb) | Buy 4, Save $2.00 |
| Bananas (bunch) | Buy 3, Save $1.00 |

The cart page shows a green "Deals applied" badge for each active deal, a "Cart Savings" row in the footer, and a hint listing all available deals. The checkout order summary reflects the same savings.

---

## Chaos Engineering Panel — `/demo`

The `⚡ Demo` button in the navbar opens a control panel that toggles fault injection on each external service without restarting the app. Changes take effect on the next checkout attempt.

### Fault modes

| Mode | Behaviour |
|---|---|
| `NORMAL` | Service operates normally |
| `DOWN` | Throws `ServiceUnavailableException` immediately |
| `SLOW` | Blocks the calling thread for `slowDelayMs` milliseconds then succeeds |
| `FLAKY` | Fails every other call — even-numbered calls throw |

### Per-service granularity

Inventory has three independent fault points (`checkAvailability`, `reserveStock`, `commitReservation`) so you can observe exactly which compensations fire depending on where in the saga the failure occurs. `releaseReservation` is never fault-injected — compensations must be reliable.

Payment has a separate timeout deadline (`paymentTimeoutMs`) that is enforced via `Future.get(timeout, MILLISECONDS)`. This means SLOW + a delay that exceeds the timeout produces a `TimeoutException` → compensation, while SLOW + a delay shorter than the timeout produces a slow-but-successful checkout.

### One-click scenario presets

| Preset | What to observe |
|---|---|
| **All normal** | Happy path — full saga completes |
| **Payment DOWN** | Steps 1–3 succeed; Step 4 fails; `releaseReservation` fires |
| **Payment SLOW** | 2 s delay, 3 s timeout — succeeds; shows cost of slow dependencies |
| **Payment TIMEOUT** | 5 s delay, 3 s timeout — `TimeoutException` at Step 4; reservation released |
| **Payment FLAKY** | Every other attempt fails; retry succeeds |
| **Idempotency OFF** | Double-submit charges the card twice |
| **Inventory DOWN (check)** | Immediate fail at Step 1; no side effects |
| **Inventory DOWN (reserve)** | Fail at Step 3; payment was never attempted |
| **Inventory DOWN (commit)** | Hardest path: Steps 1–5 succeed, Step 6 fails; full rollback — refund + cancel + release |
| **Inventory SLOW (reserve)** | Step 3 blocks the servlet request thread for 4 s — no timeout protection, unlike payment |
| **Promotion DOWN (graceful)** | Checkout proceeds; promo discount silently skipped |
| **Promotion DOWN (hard fail)** | Checkout aborts; no side effects |
| **Promotion FLAKY (graceful)** | Every other promo lookup fails silently; checkout continues at full price — read-only, no compensation needed |

### Live saga execution trace

Every failed checkout renders a colour-coded trace on the checkout page:

```
1   checkAvailability                          ✅ OK
2   applyPromotion (SAVE10)                    ✅ OK
3   reserveStock                               ✅ OK
4   processPayment — timed out after 3000ms    ❌ FAILED
↩   releaseReservation — payment timeout       ⚠ COMPENSATED
```

`DEGRADED` status appears when the promotion service is down but graceful degradation is enabled.

---

## Design Patterns Explained

### Saga Pattern (compensating transactions)

When a distributed operation cannot be wrapped in a single ACID transaction, the Saga pattern sequences local transactions and defines explicit compensating actions that undo each step on failure. The trade-off: compensations are asynchronous and can themselves fail — production systems use an outbox table and retry with exponential back-off.

### Graceful Degradation

Ask: *"Is this service on the critical path?"* If its failure prevents the core action, treat it as critical and fail loudly. If you can proceed at reduced functionality, degrade gracefully and emit a metric so the outage is visible even though the customer journey continues.

### Idempotency

Payment requests carry a UUID generated once per form load. The stub deduplicates by this key — a retry after a transient failure replays the original result, not a new charge. Real payment gateways (Stripe, Adyen) support idempotency keys natively via request headers.

### Timeouts, Thread Safety, and Circuit Breakers

**Payment** runs on a dedicated daemon-thread executor with a hard deadline (`paymentTimeoutMs`, default 3 s). On `TimeoutException`, the future is cancelled and the inventory reservation is released before the user sees an error.

**Inventory** has no such protection — `reserveStock` and `checkAvailability` execute directly on the servlet request thread. Try the *Inventory SLOW at reserve* preset: the entire HTTP request stalls for 4 seconds. Under load, a slow inventory service would exhaust the servlet thread pool and bring the application down — even though no payment was ever attempted.

The lesson: timeouts belong on every external call, not just the one that charges money. In production, wrap each call in a `CompletableFuture` with a deadline, and add Resilience4j `@CircuitBreaker` so that after N consecutive timeouts the breaker opens and subsequent calls fail fast without blocking threads at all.

### Intermittent Failures and Retry Safety

FLAKY mode fails every other call, simulating an unstable host or network partition. The safety of retrying depends entirely on whether the operation has side effects:

- **Read-only** (promotion lookup) — always safe to retry or silently skip. The *Promotion FLAKY + graceful degrade* preset shows this: every other promo lookup fails, the saga logs DEGRADED, and checkout succeeds with no compensation needed.
- **Write with side effects** (payment) — retrying without an idempotency key risks double-charging. The *Payment FLAKY* preset shows alternating failures; pairing it with idempotency ON vs. OFF demonstrates the difference. Real gateways (Stripe, Adyen) support idempotency keys natively via request headers.

In production: Resilience4j `@Retry` with exponential back-off and jitter handles transient failures automatically. Only annotate a method as retryable if it is idempotent — or if an idempotency key is guaranteed to be in the request.

### Two Sources of Truth for Stock

The inventory stub (in-memory) is authoritative for checkout and handles reserve/release in real-time. The `Product` table (H2) is a cached display value updated only after a confirmed order. The two-phase reserve/commit flow keeps them consistent without requiring a distributed lock.

---

## Production Considerations

This demo intentionally omits several things that a real system would require:

| Gap | Production solution |
|---|---|
| Compensations can fail | **Outbox table** — write compensating events to the DB in the same transaction as the order attempt; a background worker retries them with exponential back-off until they succeed. JVM crashes cannot lose events. |
| Only payment has a timeout | Wrap every external call (inventory, promotion) in a `CompletableFuture` + deadline or Resilience4j `@TimeLimiter`. |
| No circuit breaker | Resilience4j `@CircuitBreaker` opens after N consecutive failures, failing fast without blocking threads, and half-opens periodically to probe recovery. |
| No distributed tracing | Propagate a trace ID (OpenTelemetry / Micrometer Tracing) across all service calls so a failed saga can be reconstructed end-to-end in Grafana or Jaeger. |
| Idempotency key is in-process | In production, store the key in the outbox record alongside the pending order so every replay — including after a JVM restart — uses the same key. |
| No dead-letter handling | Compensating actions that fail after N retries should land in a dead-letter queue for manual review rather than being silently dropped. |

---

## Test Data

### Products (seeded by `DataInitializer` on every startup)

| Category | Name | Stock |
|---|---|---|
| Produce | Organic Apples (1 lb) | 50 |
| Produce | Bananas (bunch) | 75 |
| Produce | Baby Spinach (5 oz) | 30 |
| Produce | Roma Tomatoes (1 lb) | 3 — **Low Stock** |
| Dairy | Whole Milk (1 gal) | 40 |
| Dairy | Greek Yogurt (32 oz) | 25 |
| Dairy | Cheddar Cheese (8 oz) | 35 |
| Dairy | Free Range Eggs (dozen) | 0 — **Out of Stock** |
| Bakery | Sourdough Bread | 20 |
| Bakery | Blueberry Muffins (4-pack) | 15 |
| Bakery | Croissants (2-pack) | 2 — **Low Stock** |
| Bakery | Whole Wheat Bagels (6-pack) | 25 |
| Beverages | Orange Juice (52 oz) | 30 |
| Beverages | Sparkling Water (12-pack) | 40 |
| Beverages | Cold Brew Coffee (32 oz) | 20 |
| Beverages | Herbal Tea (20-pack) | 35 |

### Cart deal trigger quantities

| Product | Trigger | Deal |
|---|---|---|
| Whole Milk (1 gal) | qty ≥ 3 | Buy 2, Get 1 Free |
| Organic Apples (1 lb) | qty ≥ 4 | Save $2.00 |
| Bananas (bunch) | qty ≥ 3 | Save $1.00 |
