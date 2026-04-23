package com.demo.grocery.dto;

/**
 * Form-binding POJO for the checkout page ({@code POST /checkout}).
 *
 * <p>Spring MVC binds the HTML form fields to this object via {@code @ModelAttribute}.
 * The most important field is {@code idempotencyKey}: it is generated once per form load
 * in {@link com.demo.grocery.controller.CheckoutController#checkoutForm} and embedded as
 * a hidden field, so that a browser Back + resubmit or a network retry replays the same
 * key and the payment service returns the original result without a second charge.
 *
 * <p>No server-side format validation is applied to the card fields — the stub payment
 * service normalises card numbers by stripping spaces and hyphens before matching against
 * the decline table. A production integration would delegate all card handling to the
 * gateway SDK (e.g. Stripe.js) so raw PANs never reach the server.
 */
public class CheckoutRequest {

    private String promoCode;
    private String cardNumber;
    private String cardHolderName;
    private String expiryMonth;
    private String expiryYear;
    private String cvv;
    /** Generated once per form load; used by the payment service to deduplicate retries. */
    private String idempotencyKey;

    public String getPromoCode() { return promoCode; }
    public void setPromoCode(String promoCode) { this.promoCode = promoCode; }
    public String getCardNumber() { return cardNumber; }
    public void setCardNumber(String cardNumber) { this.cardNumber = cardNumber; }
    public String getCardHolderName() { return cardHolderName; }
    public void setCardHolderName(String cardHolderName) { this.cardHolderName = cardHolderName; }
    public String getExpiryMonth() { return expiryMonth; }
    public void setExpiryMonth(String expiryMonth) { this.expiryMonth = expiryMonth; }
    public String getExpiryYear() { return expiryYear; }
    public void setExpiryYear(String expiryYear) { this.expiryYear = expiryYear; }
    public String getCvv() { return cvv; }
    public void setCvv(String cvv) { this.cvv = cvv; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
