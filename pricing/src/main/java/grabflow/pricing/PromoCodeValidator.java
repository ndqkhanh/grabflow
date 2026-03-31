package grabflow.pricing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Validates promotional codes and applies discounts to ride fares.
 *
 * <h3>Design</h3>
 * <p>Promo codes are stored in a {@link ConcurrentHashMap} keyed by the
 * normalised (upper-cased) code string. Lookup is O(1) on average. Expiry
 * is checked at validation time using wall-clock {@link Instant}; no background
 * purge thread is required because the dataset is expected to be small
 * (tens to low hundreds of active promotions at once).</p>
 *
 * <h3>Thread Safety</h3>
 * <p>All public methods are thread-safe through the underlying
 * {@link ConcurrentHashMap}.</p>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 *   PromoCodeValidator validator = new PromoCodeValidator();
 *   validator.addPromoCode(new PromoCode("GRAB20", 20.0, Instant.now().plusSeconds(3600)));
 *
 *   double discounted = validator.applyDiscount(10.00, "grab20"); // -> 8.00
 * }</pre>
 */
public class PromoCodeValidator {

    private static final Logger log = LoggerFactory.getLogger(PromoCodeValidator.class);

    private final ConcurrentHashMap<String, PromoCode> promoCodes = new ConcurrentHashMap<>();

    /**
     * Registers a promo code. If a code with the same value already exists it is replaced.
     *
     * @param promo the promo code to register (must not be null)
     */
    public void addPromoCode(PromoCode promo) {
        if (promo == null) throw new IllegalArgumentException("PromoCode must not be null");
        promoCodes.put(normalise(promo.code()), promo);
        log.info("Promo code registered: {} ({}% off, expires {})",
                promo.code(), promo.discountPercent(), promo.expiresAt());
    }

    /**
     * Validates a promo code and returns it if it is known and has not expired.
     *
     * @param code the raw promo code string (case-insensitive)
     * @return the valid {@link PromoCode}, or {@link Optional#empty()} if unknown or expired
     */
    public Optional<PromoCode> validate(String code) {
        if (code == null || code.isBlank()) return Optional.empty();

        PromoCode promo = promoCodes.get(normalise(code));
        if (promo == null) {
            log.debug("Promo code not found: {}", code);
            return Optional.empty();
        }
        if (Instant.now().isAfter(promo.expiresAt())) {
            log.debug("Promo code expired: {} (expired at {})", code, promo.expiresAt());
            return Optional.empty();
        }
        log.debug("Promo code valid: {} ({}% off)", code, promo.discountPercent());
        return Optional.of(promo);
    }

    /**
     * Applies the discount for the given promo code to a fare.
     *
     * <p>If the code is invalid or expired the original fare is returned unchanged.</p>
     *
     * @param fare      original fare amount (must be &ge; 0)
     * @param promoCode promo code string (case-insensitive)
     * @return discounted fare, or the original fare if no valid promo applies
     */
    public double applyDiscount(double fare, String promoCode) {
        if (fare < 0) throw new IllegalArgumentException("Fare must be >= 0");
        return validate(promoCode)
                .map(p -> {
                    double discounted = fare * (1.0 - p.discountPercent() / 100.0);
                    log.info("Promo {} applied: {} -> {} ({}% off)",
                            p.code(), fare, discounted, p.discountPercent());
                    return discounted;
                })
                .orElse(fare);
    }

    /**
     * Returns the number of registered promo codes (including expired ones).
     */
    public int codeCount() {
        return promoCodes.size();
    }

    // ── Nested types ──

    /**
     * An immutable promotional code.
     *
     * @param code            the promo code string (as registered; lookup is case-insensitive)
     * @param discountPercent percentage discount to apply to the fare (0–100 exclusive)
     * @param expiresAt       wall-clock instant after which the code is no longer valid
     */
    public record PromoCode(String code, double discountPercent, Instant expiresAt) {

        /** Validates field constraints. */
        public PromoCode {
            if (code == null || code.isBlank())
                throw new IllegalArgumentException("Code must not be blank");
            if (discountPercent <= 0 || discountPercent >= 100)
                throw new IllegalArgumentException("discountPercent must be in (0, 100)");
            if (expiresAt == null)
                throw new IllegalArgumentException("expiresAt must not be null");
        }
    }

    // ── Helpers ──

    private static String normalise(String code) {
        return code.trim().toUpperCase();
    }
}
