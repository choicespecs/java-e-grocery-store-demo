package com.demo.grocery.external.coupon.stub;

import com.demo.grocery.demo.DemoFaultConfig;
import com.demo.grocery.demo.FaultInjector;
import com.demo.grocery.dto.CartItem;
import com.demo.grocery.external.coupon.CouponOffer;
import com.demo.grocery.external.coupon.CouponOfferHint;
import com.demo.grocery.external.coupon.CouponResult;
import com.demo.grocery.external.coupon.CouponService;
import com.demo.grocery.external.coupon.OfferType;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Simulates an external coupon / offer engine.
 *
 * Active offers:
 *   BOGO           Croissants (2-pack)                      — buy 2, get 1 free (every 2nd pack)
 *   BUNDLE         Baby Spinach (5 oz) + Greek Yogurt        — $1.50 off together
 *   QUANTITY_OFF   Blueberry Muffins (4-pack) × 2           — $2.00 off
 *   QUANTITY_OFF   Cold Brew Coffee (32 oz) × 2             — $3.00 off
 *   BUNDLE         Sourdough Bread + Cheddar Cheese          — $1.50 off together
 *   QUANTITY_OFF   Whole Wheat Bagels (6-pack) × 2          — $1.50 off
 *   BUNDLE         Herbal Tea (20-pack) + Orange Juice       — $1.50 off together
 *   SPEND_THRESHOLD Cart subtotal ≥ $20                     — $3.00 off
 *
 * getOfferCatalog() is never fault-injected — always returns static metadata
 * so the UI can show "service DOWN" rather than an empty offers list.
 */
@Service
public class StubCouponService implements CouponService {

    private static final BigDecimal SPEND_THRESHOLD = new BigDecimal("20.00");

    private static final List<CouponOfferHint> CATALOG = List.of(
        new CouponOfferHint("BOGO-CROISSANT",       "Buy 1 Get 1 Free — Croissants",
            "Add 2+ Croissants (2-pack) to unlock"),
        new CouponOfferHint("BUNDLE-SPINACH-YOGURT", "Bundle — Baby Spinach + Greek Yogurt",
            "Add both Baby Spinach and Greek Yogurt"),
        new CouponOfferHint("MUFFIN-MULTIBUY",      "Multi-buy — Blueberry Muffins, Save $2",
            "Add 2+ Blueberry Muffin packs to unlock"),
        new CouponOfferHint("COLD-BREW-SAVE3",      "Multi-buy — Cold Brew Coffee, Save $3",
            "Add 2+ Cold Brew bottles to unlock"),
        new CouponOfferHint("BUNDLE-BREAD-CHEESE",  "Bundle — Sourdough Bread + Cheddar Cheese",
            "Add both Sourdough Bread and Cheddar Cheese"),
        new CouponOfferHint("BAGEL-MULTIBUY",       "Multi-buy — Whole Wheat Bagels, Save $1.50",
            "Add 2+ Whole Wheat Bagel packs to unlock"),
        new CouponOfferHint("BUNDLE-TEA-OJ",        "Bundle — Herbal Tea + Orange Juice",
            "Add both Herbal Tea and Orange Juice"),
        new CouponOfferHint("SPEND20-SAVE3",        "Spend $20+, Save $3",
            "Reach $20 in your cart to unlock")
    );

    private final DemoFaultConfig faultConfig;

    public StubCouponService(DemoFaultConfig faultConfig) {
        this.faultConfig = faultConfig;
    }

    @Override
    public List<CouponOfferHint> getOfferCatalog() {
        return CATALOG;
    }

    @Override
    public CouponResult evaluate(Collection<CartItem> items) {
        FaultInjector.apply(faultConfig.getCouponMode(), "CouponEngine",
                faultConfig.getSlowDelayMs(), faultConfig.getCouponFlakyCounter());

        Map<String, CartItem> byName = items.stream()
                .collect(Collectors.toMap(CartItem::getProductName, i -> i));

        List<CouponOffer> offers = new ArrayList<>();

        // ── BOGO: Croissants (2-pack) ────────────────────────────────────────────────
        CartItem croissants = byName.get("Croissants (2-pack)");
        if (croissants != null && croissants.getQuantity() >= 2) {
            int freePacks = croissants.getQuantity() / 2;
            BigDecimal discount = croissants.getUnitPrice()
                    .multiply(BigDecimal.valueOf(freePacks))
                    .setScale(2, RoundingMode.HALF_UP);
            offers.add(new CouponOffer("BOGO-CROISSANT",
                    "Buy 1 Get 1 Free — Croissants",
                    freePacks + " pack" + (freePacks > 1 ? "s" : "") + " free",
                    OfferType.BOGO, discount));
        }

        // ── BUNDLE: Baby Spinach + Greek Yogurt ──────────────────────────────────────
        if (byName.containsKey("Baby Spinach (5 oz)") && byName.containsKey("Greek Yogurt (32 oz)")) {
            offers.add(new CouponOffer("BUNDLE-SPINACH-YOGURT",
                    "Bundle — Baby Spinach + Greek Yogurt",
                    "Save $1.50 when bought together",
                    OfferType.BUNDLE, new BigDecimal("1.50")));
        }

        // ── QUANTITY OFF: Blueberry Muffins × 2 → $2 off ────────────────────────────
        CartItem muffins = byName.get("Blueberry Muffins (4-pack)");
        if (muffins != null && muffins.getQuantity() >= 2) {
            offers.add(new CouponOffer("MUFFIN-MULTIBUY",
                    "Multi-buy — Blueberry Muffins, Save $2",
                    "$2.00 off when you buy 2 or more packs",
                    OfferType.QUANTITY_OFF, new BigDecimal("2.00")));
        }

        // ── QUANTITY OFF: Cold Brew Coffee × 2 → $3 off ─────────────────────────────
        CartItem coldBrew = byName.get("Cold Brew Coffee (32 oz)");
        if (coldBrew != null && coldBrew.getQuantity() >= 2) {
            offers.add(new CouponOffer("COLD-BREW-SAVE3",
                    "Multi-buy — Cold Brew Coffee, Save $3",
                    "$3.00 off when you buy 2 or more bottles",
                    OfferType.QUANTITY_OFF, new BigDecimal("3.00")));
        }

        // ── BUNDLE: Sourdough Bread + Cheddar Cheese → $1.50 off ────────────────────
        if (byName.containsKey("Sourdough Bread") && byName.containsKey("Cheddar Cheese (8 oz)")) {
            offers.add(new CouponOffer("BUNDLE-BREAD-CHEESE",
                    "Bundle — Sourdough Bread + Cheddar Cheese",
                    "$1.50 off when bought together",
                    OfferType.BUNDLE, new BigDecimal("1.50")));
        }

        // ── QUANTITY OFF: Whole Wheat Bagels × 2 → $1.50 off ───────────────────────
        CartItem bagels = byName.get("Whole Wheat Bagels (6-pack)");
        if (bagels != null && bagels.getQuantity() >= 2) {
            offers.add(new CouponOffer("BAGEL-MULTIBUY",
                    "Multi-buy — Whole Wheat Bagels, Save $1.50",
                    "$1.50 off when you buy 2 or more packs",
                    OfferType.QUANTITY_OFF, new BigDecimal("1.50")));
        }

        // ── BUNDLE: Herbal Tea + Orange Juice → $1.50 off ───────────────────────────
        if (byName.containsKey("Herbal Tea (20-pack)") && byName.containsKey("Orange Juice (52 oz)")) {
            offers.add(new CouponOffer("BUNDLE-TEA-OJ",
                    "Bundle — Herbal Tea + Orange Juice",
                    "$1.50 off when bought together",
                    OfferType.BUNDLE, new BigDecimal("1.50")));
        }

        // ── SPEND THRESHOLD: cart subtotal ≥ $20 → $3 off ──────────────────────────
        BigDecimal subtotal = items.stream()
                .map(CartItem::getLineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (subtotal.compareTo(SPEND_THRESHOLD) >= 0) {
            offers.add(new CouponOffer("SPEND20-SAVE3",
                    "Spend $20+, Save $3",
                    "$3.00 off your order",
                    OfferType.SPEND_THRESHOLD, new BigDecimal("3.00")));
        }

        if (offers.isEmpty()) return CouponResult.none();
        BigDecimal total = offers.stream()
                .map(CouponOffer::discount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new CouponResult(offers, total);
    }
}
