# Data Flow

This document traces the runtime data flows through the application: the checkout saga (the centrepiece), the cart mutation and discount pipeline, the fault injection path, and the session lifecycle.

---

## Contents

1. [Checkout Saga — Happy Path](#checkout-saga--happy-path)
2. [Checkout Saga — Compensation Paths](#checkout-saga--compensation-paths)
3. [Cart Mutation and Discount Pipeline](#cart-mutation-and-discount-pipeline)
4. [Payment Thread Isolation](#payment-thread-isolation)
5. [Fault Injection Data Path](#fault-injection-data-path)
6. [Session Lifecycle](#session-lifecycle)
7. [Discount Calculation Breakdown](#discount-calculation-breakdown)
8. [Stock State Machine](#stock-state-machine)
9. [Data Models](#data-models)

---

## Checkout Saga — Happy Path

`CheckoutService.checkout()` coordinates six steps across three external services. The method is intentionally not `@Transactional` — the saga spans system boundaries that cannot share a JDBC transaction.

```mermaid
sequenceDiagram
    autonumber
    participant Browser
    participant CheckoutController
    participant CheckoutService
    participant InventoryService
    participant PromotionService
    participant PaymentExecutor as Payment Thread
    participant PaymentService
    participant OrderService
    participant H2 as H2 Database

    Browser->>CheckoutController: POST /checkout (card, promoCode, idempotencyKey)
    CheckoutController->>CheckoutService: checkout(cart, request, trace)

    Note over CheckoutService,InventoryService: Step 1 — Read-only pre-flight (no side effects)
    CheckoutService->>InventoryService: checkAvailability(quantities)
    InventoryService-->>CheckoutService: OK

    Note over CheckoutService,PromotionService: Step 2 — Promo code (read-only, non-critical)
    CheckoutService->>PromotionService: applyPromotion(code, subtotal)
    PromotionService-->>CheckoutService: PromotionResult(discount)

    Note over CheckoutService: Compute finalTotal = subtotal - cartPromoDiscount - couponDiscount - promoDiscount

    Note over CheckoutService,InventoryService: Step 3 — SIDE EFFECT: reserve stock
    CheckoutService->>InventoryService: reserveStock(quantities)
    InventoryService-->>CheckoutService: InventoryReservation(id, items)

    Note over CheckoutService,PaymentService: Step 4 — SIDE EFFECT: charge card (on dedicated thread)
    CheckoutService->>PaymentExecutor: submit(() -> processPayment(request))
    PaymentExecutor->>PaymentService: processPayment(PaymentRequest)
    PaymentService-->>PaymentExecutor: PaymentResult(token, amount, SUCCESS)
    PaymentExecutor-->>CheckoutService: Future.get(paymentTimeoutMs) → PaymentResult

    Note over CheckoutService,H2: Step 5 — SIDE EFFECT: DB write (@Transactional)
    CheckoutService->>OrderService: createOrder(cart, promotion, payment, finalTotal)
    OrderService->>H2: INSERT grocery_order + order_item rows
    OrderService->>H2: UPDATE product.stock_quantity -= qty (display cache)
    H2-->>OrderService: committed
    OrderService-->>CheckoutService: Order(id=N, status=CONFIRMED)

    Note over CheckoutService,InventoryService: Step 6 — Commit reservation (makes hold permanent)
    CheckoutService->>InventoryService: commitReservation(reservation)
    InventoryService-->>CheckoutService: OK (reservation record removed)

    CheckoutService-->>CheckoutController: Order(id=N)
    CheckoutController->>Browser: redirect /checkout/confirmation/N
```

---

## Checkout Saga — Compensation Paths

Each step that carries a side effect defines an explicit compensation. Compensations fire in reverse step order.

```mermaid
flowchart TD
    Start([POST /checkout]) --> S1

    S1["Step 1\ncheckAvailability\nread-only"]
    S1 -->|OK| S2
    S1 -->|InsufficientStockException\nServiceUnavailableException| E1

    E1["FAIL — abort\nno side effects\nsafe to retry"]

    S2["Step 2\napplyPromotion\nread-only"]
    S2 -->|OK| S3
    S2 -->|InvalidPromotionException| E2A
    S2 -->|ServiceUnavailableException\ngracefulDegradation=ON| S2D
    S2 -->|ServiceUnavailableException\ngracefulDegradation=OFF| E2B

    E2A["FAIL — invalid code\nno side effects"]
    S2D["DEGRADED\ncontinues at full price"]
    E2B["FAIL — abort\nno side effects"]
    S2D --> S3

    S3["Step 3\nreserveStock\nSIDE EFFECT: stock held"]
    S3 -->|OK| S4
    S3 -->|Exception| E3

    E3["FAIL — abort\npayment never attempted\nno compensation needed"]

    S4["Step 4\nprocessPayment\nSIDE EFFECT: card charged\nruns on payment thread"]
    S4 -->|OK| S5
    S4 -->|PaymentFailedException\nServiceUnavailableException\nTimeoutException| C4

    C4["COMPENSATION\nreleaseReservation\nstock returned to pool"]
    C4 --> E4["FAIL — no charge\nstock restored"]

    S5["Step 5\ncreateOrder\nSIDE EFFECT: DB write"]
    S5 -->|OK| S6
    S5 -->|Exception| C5A

    C5A["COMPENSATION\nrefund(paymentToken)"]
    C5A --> C5B["COMPENSATION\nreleaseReservation"]
    C5B --> E5["FAIL — charge reversed\nstock restored\nno order row"]

    S6["Step 6\ncommitReservation\nfinalises hold"]
    S6 -->|OK| Done(["SUCCESS\nredirect /confirmation"])
    S6 -->|Exception| C6A

    C6A["COMPENSATION\nrefund(paymentToken)"]
    C6A --> C6B["COMPENSATION\ncancelOrder — status=CANCELLED"]
    C6B --> C6C["COMPENSATION\nreleaseReservation"]
    C6C --> E6["FAIL — charge reversed\norder cancelled\nstock restored\nDisplay stock diverges from authoritative"]
```

### Compensation map

| Failure at | Compensations fired (in order) | Money moved? | Order created? | Stock released? |
|---|---|---|---|---|
| Step 1 — checkAvailability | None | No | No | N/A |
| Step 2 — applyPromotion (hard fail) | None | No | No | N/A |
| Step 2 — applyPromotion (graceful) | None — continues DEGRADED | No | No | N/A |
| Step 3 — reserveStock | None | No | No | N/A — never reserved |
| Step 4 — processPayment (any failure) | releaseReservation | No | No | Yes |
| Step 5 — createOrder | refund → releaseReservation | Reversed | No | Yes |
| Step 6 — commitReservation | refund → cancelOrder → releaseReservation | Reversed | Cancelled | Yes |

---

## Cart Mutation and Discount Pipeline

Every cart write (add, update, remove) and every `GET /cart` call triggers `refreshDiscounts()`. Two discount streams are evaluated in sequence.

```mermaid
sequenceDiagram
    participant Browser
    participant CartController
    participant CartPromotionService
    participant CouponService
    participant Cart

    Browser->>CartController: POST /cart/add?productId=X&quantity=N

    CartController->>Cart: addItem(product, allowedQty)
    CartController->>CartPromotionService: evaluate(cart.getItems())
    Note over CartPromotionService: No fault injection — always returns
    CartPromotionService-->>CartController: CartPromotionResult(deals, totalDiscount)
    CartController->>Cart: applyCartPromotion(result)

    CartController->>CouponService: evaluate(cart.getItems())
    alt CouponService available
        CouponService-->>CartController: CouponResult(offers, totalDiscount)
        CartController->>Cart: applyCoupons(result)
        CartController->>Cart: setCouponServiceDown(false)
    else ServiceUnavailableException
        CartController->>Cart: applyCoupons(CouponResult.none())
        CartController->>Cart: setCouponServiceDown(true)
    end

    CartController->>Browser: redirect /
```

`CartPromotionService` is never fault-injected and always succeeds. `CouponService` is fault-injectable; failures are caught gracefully — the cart continues working with zero coupon discount and a `couponServiceDown` flag set for the UI.

---

## Payment Thread Isolation

Payment is the only external call with dedicated thread isolation and a hard deadline. All other external calls run on the servlet request thread.

```mermaid
sequenceDiagram
    participant ServletThread as Servlet Request Thread
    participant PaymentExecutor as CachedThreadPool (payment-call daemon)
    participant PaymentService

    ServletThread->>PaymentExecutor: submit(() -> paymentService.processPayment(req))
    Note over ServletThread: paymentFuture.get(paymentTimeoutMs, MILLISECONDS)

    PaymentExecutor->>PaymentService: processPayment(PaymentRequest)

    alt Payment completes within timeout
        PaymentService-->>PaymentExecutor: PaymentResult
        PaymentExecutor-->>ServletThread: PaymentResult (via Future.get)
        Note over ServletThread: Continue to Step 5
    else TimeoutException (slowDelay > paymentTimeoutMs)
        Note over PaymentExecutor: Payment thread still running (may complete after cancel)
        ServletThread->>PaymentExecutor: paymentFuture.cancel(true)
        ServletThread->>PaymentService: releaseReservation(reservation)
        ServletThread-->>ServletThread: throw ServiceUnavailableException("timed out")
    else ExecutionException (DOWN or FLAKY or declined)
        ServletThread->>PaymentService: releaseReservation(reservation)
        ServletThread-->>ServletThread: throw PaymentFailedException or ServiceUnavailableException
    end
```

Inventory, promotion, and coupon calls have no equivalent protection. A slow inventory response blocks the servlet thread for the full `slowDelayMs` duration with no timeout escape.

---

## Fault Injection Data Path

`DemoController` writes `DemoFaultConfig`; stubs read it on each call; `FaultInjector.apply()` translates the mode into behaviour.

```mermaid
sequenceDiagram
    participant Browser
    participant DemoController
    participant DemoFaultConfig
    participant StubService as Any Stub Service
    participant FaultInjector

    Browser->>DemoController: POST /demo/preset?scenario=payment-down
    DemoController->>DemoFaultConfig: resetAll()
    DemoController->>DemoFaultConfig: setPaymentMode(DOWN)
    DemoController->>Browser: redirect /demo (flash: "Payment service DOWN...")

    Note over Browser: Next checkout attempt...

    Browser->>StubService: (called from CheckoutService)
    StubService->>DemoFaultConfig: getPaymentMode() → DOWN
    StubService->>FaultInjector: apply(DOWN, "Payment", slowMs, counter)
    FaultInjector-->>StubService: throw ServiceUnavailableException
    StubService-->>StubService: exception propagates
```

`FaultMode.FLAKY` uses a per-service `AtomicInteger` counter: even-numbered calls throw, odd-numbered calls pass through. Counters reset when `resetAll()` is called via the "All Normal" preset.

---

## Session Lifecycle

```mermaid
stateDiagram-v2
    [*] --> EmptyCart: New HTTP session
    EmptyCart --> ItemsInCart: POST /cart/add
    ItemsInCart --> ItemsInCart: POST /cart/update\nPOST /cart/remove\n(discounts re-evaluated)
    ItemsInCart --> EmptyCart: POST /cart/clear
    ItemsInCart --> CheckoutInProgress: GET /checkout
    CheckoutInProgress --> CheckoutInProgress: POST /checkout (failure)
    CheckoutInProgress --> EmptyCart: POST /checkout (success)\ncart.clear()
    EmptyCart --> [*]: Session expires / invalidation
```

`Cart` is a `@SessionScope @Component`. It implements `Serializable` so it can survive session persistence (e.g. if the servlet container serializes sessions to disk). `CartItem` also implements `Serializable` for the same reason.

---

## Discount Calculation Breakdown

Three discount streams combine to produce the `finalTotal` charged at payment. They are applied in this order:

```mermaid
flowchart TD
    SUB["Subtotal\nsum of unitPrice × qty for all items"]
    CD["CartPromotionDiscount\nautomatic quantity deals\nfrom CartPromotionService"]
    COU["CouponDiscount\nBOGO, bundles, spend thresholds\nfrom CouponService"]
    PRO["PromoCodeDiscount\npercentage or flat off subtotal\nfrom PromotionService at checkout"]
    FT["finalTotal\nmax(0, subtotal - cartPromo - coupon - promo)"]

    SUB --> FT
    CD -->|subtracted| FT
    COU -->|subtracted| FT
    PRO -->|subtracted| FT
```

`Cart.getEffectiveTotal()` returns `subtotal - cartPromotionDiscount - couponDiscount` (used for display). `CheckoutService` applies the promo code discount on top at runtime, producing `finalTotal` which is what the payment service charges.

The floor is zero — a combination of discounts can never result in a negative charge.

---

## Stock State Machine

```mermaid
stateDiagram-v2
    [*] --> Available: DataInitializer seeds stock

    Available --> Reserved: reserveStock()\navailable -= qty\nreservations[id] = qty

    Reserved --> Available: releaseReservation()\nreservations.remove(id)\navailable += qty

    Reserved --> Committed: commitReservation()\nreservations.remove(id)\n(available already decremented)

    Committed --> [*]: Stock permanently consumed
```

`releaseReservation` is never fault-injected. Compensations must succeed unconditionally — a faulted release would leave stock permanently locked with no mechanism for recovery in the demo.

---

## Data Models

### Cart → CheckoutService data flow

```mermaid
flowchart LR
    subgraph Session["HTTP Session"]
        Cart["Cart\n- items: Map of Long to CartItem\n- cartPromotion: CartPromotionResult\n- couponResult: CouponResult\n- couponServiceDown: boolean"]
        CartItem["CartItem\n- productId: Long\n- productName: String\n- unitPrice: BigDecimal\n- quantity: int"]
        Cart --> CartItem
    end

    subgraph CheckoutRequest["CheckoutRequest (form binding)"]
        CR["- promoCode: String\n- cardNumber: String\n- cardHolderName: String\n- expiryMonth: String\n- expiryYear: String\n- cvv: String\n- idempotencyKey: UUID"]
    end

    subgraph CheckoutService["CheckoutService inputs"]
        CS["checkout(cart, request, trace)"]
    end

    Cart --> CS
    CR --> CS
```

### Order persistence model

```mermaid
erDiagram
    GROCERY_ORDER {
        BIGINT id PK
        VARCHAR status "CONFIRMED or CANCELLED"
        DECIMAL subtotal "sum of line totals before discounts"
        DECIMAL discount "promo code discount only"
        DECIMAL total "final charged amount"
        VARCHAR promo_code "nullable"
        VARCHAR payment_token "PAY-XXXXXXXX from PaymentService"
        TIMESTAMP created_at
    }

    ORDER_ITEM {
        BIGINT id PK
        BIGINT order_id FK
        BIGINT product_id "snapshot — no FK constraint"
        VARCHAR product_name "snapshot"
        DECIMAL unit_price "snapshot at time of purchase"
        INT quantity
    }

    GROCERY_ORDER ||--o{ ORDER_ITEM : "contains"
```

`ORDER_ITEM` stores a snapshot of product data rather than a foreign key. This means the order record is self-contained and survives product deletions, price changes, or catalogue reorganisations.
