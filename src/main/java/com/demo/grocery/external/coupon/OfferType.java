package com.demo.grocery.external.coupon;

public enum OfferType {
    BOGO,             // every Nth unit free (e.g. buy 1 get 1)
    BUNDLE,           // two distinct products bought together get a fixed discount
    SPEND_THRESHOLD,  // cart subtotal >= X → fixed amount off
    QUANTITY_OFF      // buy N+ of one product → fixed amount off
}
