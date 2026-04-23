# Security

This document describes the security posture of the Fresh Groceries demo application: its authentication model, input entry points, data handling practices, and the gaps that are intentionally present because this is an educational demo rather than a production system.

> **Important:** This application is a local development demo with no authentication, no HTTPS, and simulated payment processing. It must not be deployed to a public-facing environment without a complete security review and significant hardening. The H2 console alone provides unrestricted SQL access to all data.

---

## Contents

1. [Authentication and Authorisation](#authentication-and-authorisation)
2. [Input Entry Points](#input-entry-points)
3. [Input Validation and Sanitisation](#input-validation-and-sanitisation)
4. [Payment Data Handling](#payment-data-handling)
5. [Session Security](#session-security)
6. [SQL Injection Surface](#sql-injection-surface)
7. [Cross-Site Request Forgery](#cross-site-request-forgery)
8. [Cross-Site Scripting](#cross-site-scripting)
9. [Sensitive Data in Memory](#sensitive-data-in-memory)
10. [Hardcoded Values](#hardcoded-values)
11. [Administrative Interfaces](#administrative-interfaces)
12. [Dependency Security](#dependency-security)
13. [Known Gaps and Production Requirements](#known-gaps-and-production-requirements)

---

## Authentication and Authorisation

**Current state:** There is no authentication. Every endpoint is accessible to any caller with network access to the application.

- No Spring Security dependency is present in `pom.xml`.
- No user accounts, sessions tied to identities, or role-based access control exist.
- The chaos engineering panel (`/demo`) and H2 console (`/h2-console`) are fully open.

**Risk for public deployment:** Critical. The `/demo` endpoint allows arbitrary fault injection; the H2 console provides unrestricted read/write SQL access to all application data.

**Production requirement:** Add Spring Security with at minimum HTTP Basic or form-based authentication protecting `/demo`, `/h2-console`, and any administrative endpoints. The storefront (`/`, `/cart`, `/checkout`) should remain public or require a customer login depending on the business model.

---

## Input Entry Points

All user-controlled input enters via HTTP request parameters bound by Spring MVC.

| Endpoint | Method | User-controlled inputs |
|---|---|---|
| `GET /` | GET | `category` query param |
| `POST /cart/add` | POST | `productId` (Long), `quantity` (int) |
| `POST /cart/update` | POST | `productId` (Long), `quantity` (int) |
| `POST /cart/remove` | POST | `productId` (Long) |
| `POST /checkout` | POST | `promoCode`, `cardNumber`, `cardHolderName`, `expiryMonth`, `expiryYear`, `cvv`, `idempotencyKey` |
| `POST /demo` | POST | `inventoryCheckMode`, `inventoryReserveMode`, `inventoryCommitMode`, `paymentMode`, `paymentIdempotencyEnabled`, `promotionMode`, `promotionGracefulDegradation`, `couponMode`, `slowDelayMs`, `paymentTimeoutMs` |
| `POST /demo/preset` | POST | `scenario` (string matched by switch expression) |
| `POST /demo/run` | POST | None — uses session cart and hardcoded test card |
| `GET /checkout/confirmation/{id}` | GET | `id` path variable (Long) |

---

## Input Validation and Sanitisation

### Numeric parameters

`productId`, `quantity`, `slowDelayMs`, `paymentTimeoutMs`, and confirmation `id` are bound as Java primitives (`Long`, `int`). Spring MVC rejects non-numeric values with a 400 Bad Request before the controller method is invoked.

- `quantity` is capped to `product.getStockQuantity()` in `CartController.addItem()` and `CartController.updateQuantity()`. A request for a negative quantity is treated as zero and removes the item.
- `slowDelayMs` and `paymentTimeoutMs` are written directly to `DemoFaultConfig` with no bounds checking. A caller could set `slowDelayMs=Integer.MAX_VALUE`, causing every subsequent request to block the calling thread indefinitely. This is acceptable in a local demo; it would be a denial-of-service vector if exposed publicly.

### Enum parameters

`inventoryCheckMode`, `inventoryReserveMode`, `inventoryCommitMode`, `paymentMode`, `promotionMode`, and `couponMode` are bound to `FaultMode` enum values. Spring MVC throws a `MethodArgumentTypeMismatchException` (400) for unknown values.

### String parameters

`promoCode`, `cardNumber`, `cardHolderName`, `expiryMonth`, `expiryYear`, `cvv`, and `idempotencyKey` are plain strings with no length limits or format enforcement at the controller level.

- `promoCode` is converted to uppercase and trimmed in `StubPromotionService`. An invalid code throws `InvalidPromotionException`, which the controller catches and renders as an inline form error.
- `cardNumber` is stripped of spaces and hyphens in `StubPaymentService` before matching against the decline table. No Luhn check or format validation is performed.
- `idempotencyKey` is used as a `ConcurrentHashMap` key. No length limit means a caller could supply an arbitrarily long string. In a demo this is inconsequential; in production, keys should be length-capped.

### scenario parameter (`/demo/preset`)

The `scenario` string is matched against a `switch` expression with a `default` case that returns "Unknown preset." Unknown scenarios reset all faults via `faultConfig.resetAll()` and display the default message. There is no injection risk because the switch result is only used to set `DemoFaultConfig` fields and populate a flash message.

---

## Payment Data Handling

**Card data is not stored.** `StubPaymentService.processPayment()` reads the card number to look up a decline reason and then discards it. The card number is never written to the H2 database, logged, or cached.

The `PaymentResult` returned by the stub contains only a `paymentToken` (a randomly generated `"PAY-" + UUID` string) and the amount. This token is stored in `Order.paymentToken` in the database — it is a reference, not card data.

The `idempotency cache` in `StubPaymentService` maps idempotency keys to `PaymentResult` records. Neither field contains card data.

**Logs:** `spring.jpa.show-sql=true` is enabled in `application.properties`. Hibernate logs all SQL statements. No SQL statement contains card data (card numbers are never written to the DB), but this setting should be disabled in any non-local deployment to avoid verbose log output.

**Production requirement:** Card data must never be logged, stored, or transmitted to any system other than a PCI DSS-compliant payment gateway. Use a real gateway SDK (Stripe.js, Adyen Web Components) that tokenises the card in the browser before it reaches the server. The server-side code should only ever see a payment method token, never raw card numbers.

---

## Session Security

`Cart` is a `@SessionScope @Component` backed by the standard Java servlet session.

- Session cookies are managed by the embedded Tomcat with default settings: no `Secure` flag (HTTP only in local dev), `HttpOnly` flag is set by default in Spring Boot.
- Session IDs are generated by Tomcat's standard `SecureRandom`-based generator.
- There is no session fixation protection because there is no login — sessions are created on first access and are anonymous throughout their lifetime.
- Cart data is serialised to the session. `Cart` and `CartItem` implement `Serializable`. No sensitive data (card numbers, CVVs) is stored in the cart or the session.

**Production requirement:** Enable HTTPS (TLS) and set `server.servlet.session.cookie.secure=true`. Add `server.servlet.session.cookie.same-site=strict` to mitigate CSRF on session cookies. Consider pinning session lifetime with `server.servlet.session.timeout`.

---

## SQL Injection Surface

All database access goes through Spring Data JPA. Queries are either:

1. Derived queries (`findById`, `findAll`, `findByCategoryName`) — generated by Spring Data, fully parameterised.
2. One custom JPQL `@Query` in `ProductRepository.decrementStock()` — uses named parameters (`:id`, `:qty`), not string concatenation. Parameterised JPQL is not vulnerable to SQL injection.

There is no native SQL, no `JdbcTemplate`, and no string-concatenated query anywhere in the codebase.

**Assessment:** SQL injection risk is negligible given the ORM usage.

---

## Cross-Site Request Forgery

Spring Security's CSRF protection is not active because Spring Security is not on the classpath. All POST endpoints are vulnerable to CSRF from any page that can make cross-origin form submissions.

The `/demo/preset` and `/demo` endpoints are the highest-risk targets: a malicious page could submit a form to `POST /demo/preset?scenario=inventory-commit-down` and silently break the application for all users on that server.

**Production requirement:** Add Spring Security and enable CSRF token validation (the default when Spring Security is present). For REST APIs, use the `SameSite=Strict` cookie attribute and `X-Requested-With` header validation.

---

## Cross-Site Scripting

Thymeleaf escapes all template expressions by default. `th:text` and `th:value` attributes HTML-encode their content before output. An XSS payload in a product name, promo code, or error message would be rendered as escaped text, not executed as script.

The saga trace (`SagaTrace.Step.detail`) is rendered in the template — if an external service returned a malicious string in an exception message, Thymeleaf would escape it before rendering.

**Assessment:** XSS risk is low given Thymeleaf's default escaping. No `th:utext` (unescaped) is used.

---

## Sensitive Data in Memory

The following data exists in memory at runtime:

| Data | Location | Retention |
|---|---|---|
| `StubInventoryService.available` | `ConcurrentHashMap<Long, Integer>` | Lifetime of the application process |
| `StubInventoryService.reservations` | `ConcurrentHashMap<String, Map<Long, Integer>>` | Cleared on commit or release |
| `StubPaymentService.settled` | `ConcurrentHashMap<String, BigDecimal>` | Lifetime of the process |
| `StubPaymentService.idempotentCache` | `ConcurrentHashMap<String, PaymentResult>` | Lifetime of the process; invalidated on refund |
| `DemoFaultConfig` fields | Singleton `@Component` | Lifetime of the process |
| `Cart` (session) | HTTP session, one per active user | Session lifetime |

No card numbers, CVVs, or PAN data are stored in any of these structures. `PaymentResult` contains only the generated token string and the amount.

**Hardcoded secrets: 0.** There are no API keys, passwords, or secret tokens in source code. The only credentials present are H2 defaults (`username=sa`, no password) defined in `application.properties`, which are appropriate for an in-memory development database.

---

## Hardcoded Values

The following values are hardcoded for demo purposes:

| Value | Location | Notes |
|---|---|---|
| Test card numbers (`4111...`, `4000...`) | `StubPaymentService.DECLINE_REASONS` | Map literal; demo only |
| Promo codes (`SAVE10`, `SAVE20`, `FLAT5`, `WELCOME`, `FRESH`, `BIG25`, `HALFOFF`) | `StubPromotionService.PROMOS` | Map literal; demo only |
| Deal definitions (Whole Milk, Organic Apples, Bananas, etc.) | `StubCartPromotionService.DEALS` | List literal; demo only |
| Coupon offer catalog | `StubCouponService.CATALOG` | List literal; demo only |
| H2 JDBC URL (`jdbc:h2:mem:grocerydb`) | `application.properties` | In-memory; no persistent data |
| Default `slowDelayMs` (5000 ms) | `DemoFaultConfig` | Field default |
| Default `paymentTimeoutMs` (3000 ms) | `DemoFaultConfig` | Field default |
| Demo test card `4111111111111111` | `DemoController.runTest()` | Hardcoded for automated demo run |
| Demo promo code `SAVE10` | `DemoController.runTest()` | Hardcoded to ensure promotion step fires |

None of these values represent production secrets or credentials.

---

## Administrative Interfaces

Two interfaces require protection if the application is ever network-accessible:

### `/demo` — Chaos Engineering Panel

`DemoController` reads and writes `DemoFaultConfig`. A caller who can POST to `/demo` or `/demo/preset` can:
- Make all external services appear down to every user
- Set `slowDelayMs` to a large value, causing all checkout-adjacent requests to block
- Disable payment idempotency, causing double-charges on retry

### `/h2-console` — H2 In-Process SQL Console

Enabled in `application.properties` (`spring.h2.console.enabled=true`). A caller who can access this console has full SQL read/write access to all application data including the `grocery_order` table (orders and payment tokens) and the `product` table.

**Production requirement:** Disable both in any non-local deployment:

```properties
spring.h2.console.enabled=false
```

And either remove `DemoController` and `DemoFaultConfig` from the production build, or protect `/demo/**` behind an admin role.

---

## Dependency Security

| Dependency | Version source | Notes |
|---|---|---|
| Spring Boot | 3.4.4 (parent POM) | Manages all transitive dependency versions |
| H2 Database | Managed by Spring Boot BOM | Runtime scope only |
| Thymeleaf | Managed by Spring Boot BOM | Auto-escapes output |
| Spring Data JPA / Hibernate | Managed by Spring Boot BOM | |

All dependency versions are controlled by the Spring Boot 3.4.4 BOM (Bill of Materials), which tracks security-patched versions. Running `./mvnw dependency:tree` will show the full resolved dependency graph.

**Recommendation:** Run `./mvnw org.owasp:dependency-check-maven:check` periodically to scan for known CVEs in the dependency tree. This is not configured in the current `pom.xml`.

---

## Known Gaps and Production Requirements

| Gap | Severity | Production requirement |
|---|---|---|
| No authentication on any endpoint | Critical | Spring Security with role-based access; admin endpoints require at minimum HTTP Basic or token auth |
| H2 console enabled and unrestricted | Critical | Disable in any non-local deployment |
| `/demo` panel has no access control | High | Restrict to admin role; or remove from production build |
| No HTTPS / TLS | High | Enable TLS; set `Secure` flag on session cookie |
| No CSRF protection | High | Spring Security CSRF tokens (enabled by default when Spring Security is added) |
| `spring.jpa.show-sql=true` | Medium | Disable to prevent verbose SQL in production logs |
| No card data in code — but real gateway not integrated | High | Use Stripe.js / Adyen Web Components to tokenise cards in the browser; server never sees raw PANs |
| Session is anonymous and unbound to identity | Medium | Associate session cart with authenticated user or anonymous token stored in a cookie with `SameSite=Strict` |
| No rate limiting on checkout | Medium | Apply rate limiting per session/IP on `POST /checkout` to prevent brute-force card testing |
| `slowDelayMs` has no upper bound | Low | Validate and cap at a reasonable maximum (e.g. 30 s) to prevent self-inflicted denial of service |
