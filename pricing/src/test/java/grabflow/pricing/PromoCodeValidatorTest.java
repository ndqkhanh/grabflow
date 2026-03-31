package grabflow.pricing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;

class PromoCodeValidatorTest {

    private PromoCodeValidator validator;

    @BeforeEach
    void setUp() {
        validator = new PromoCodeValidator();
    }

    private PromoCodeValidator.PromoCode validPromo(String code, double pct) {
        return new PromoCodeValidator.PromoCode(code, pct, Instant.now().plusSeconds(3600));
    }

    private PromoCodeValidator.PromoCode expiredPromo(String code, double pct) {
        return new PromoCodeValidator.PromoCode(code, pct, Instant.now().minusSeconds(1));
    }

    // ── validate() ────────────────────────────────────────────────────────

    @Test
    void validCodeReturnsPromo() {
        validator.addPromoCode(validPromo("GRAB20", 20.0));
        Optional<PromoCodeValidator.PromoCode> result = validator.validate("GRAB20");
        assertThat(result).isPresent();
        assertThat(result.get().discountPercent()).isEqualTo(20.0);
    }

    @Test
    void lookupIsCaseInsensitive() {
        validator.addPromoCode(validPromo("GRAB20", 20.0));
        assertThat(validator.validate("grab20")).isPresent();
        assertThat(validator.validate("Grab20")).isPresent();
        assertThat(validator.validate("GRAB20")).isPresent();
    }

    @Test
    void expiredCodeIsRejected() {
        validator.addPromoCode(expiredPromo("EXPIRED10", 10.0));
        assertThat(validator.validate("EXPIRED10")).isEmpty();
    }

    @Test
    void unknownCodeIsRejected() {
        assertThat(validator.validate("NONEXISTENT")).isEmpty();
    }

    @Test
    void nullCodeReturnsEmpty() {
        assertThat(validator.validate(null)).isEmpty();
    }

    @Test
    void blankCodeReturnsEmpty() {
        assertThat(validator.validate("   ")).isEmpty();
    }

    // ── applyDiscount() ───────────────────────────────────────────────────

    @Test
    void validCodeAppliesDiscount() {
        validator.addPromoCode(validPromo("SAVE20", 20.0));
        double result = validator.applyDiscount(10.00, "SAVE20");
        assertThat(result).isCloseTo(8.00, within(0.001));
    }

    @Test
    void fiftyPercentDiscount() {
        validator.addPromoCode(validPromo("HALF", 50.0));
        double result = validator.applyDiscount(20.00, "HALF");
        assertThat(result).isCloseTo(10.00, within(0.001));
    }

    @Test
    void expiredCodeLeaveFareUnchanged() {
        validator.addPromoCode(expiredPromo("OLD10", 10.0));
        double result = validator.applyDiscount(10.00, "OLD10");
        assertThat(result).isCloseTo(10.00, within(0.001));
    }

    @Test
    void unknownCodeLeavesFareUnchanged() {
        double result = validator.applyDiscount(15.00, "FAKE");
        assertThat(result).isCloseTo(15.00, within(0.001));
    }

    @Test
    void discountAppliedCaseInsensitively() {
        validator.addPromoCode(validPromo("LOWER", 25.0));
        assertThat(validator.applyDiscount(8.00, "lower")).isCloseTo(6.00, within(0.001));
    }

    @Test
    void negativeFareThrows() {
        assertThatThrownBy(() -> validator.applyDiscount(-5.00, "CODE"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── addPromoCode() ────────────────────────────────────────────────────

    @Test
    void addingDuplicateCodeReplacesOld() {
        validator.addPromoCode(validPromo("DUP", 10.0));
        validator.addPromoCode(validPromo("DUP", 30.0));
        Optional<PromoCodeValidator.PromoCode> result = validator.validate("DUP");
        assertThat(result).isPresent();
        assertThat(result.get().discountPercent()).isEqualTo(30.0);
    }

    @Test
    void nullPromoCodeThrows() {
        assertThatThrownBy(() -> validator.addPromoCode(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void codeCountTracksRegisteredCodes() {
        assertThat(validator.codeCount()).isZero();
        validator.addPromoCode(validPromo("A", 10.0));
        validator.addPromoCode(validPromo("B", 20.0));
        assertThat(validator.codeCount()).isEqualTo(2);
    }

    // ── PromoCode record validation ───────────────────────────────────────

    @Test
    void promoCodeWithBlankCodeThrows() {
        assertThatThrownBy(() -> new PromoCodeValidator.PromoCode("", 10.0, Instant.now().plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void promoCodeWithZeroDiscountThrows() {
        assertThatThrownBy(() -> new PromoCodeValidator.PromoCode("X", 0.0, Instant.now().plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void promoCodeWithHundredPercentDiscountThrows() {
        assertThatThrownBy(() -> new PromoCodeValidator.PromoCode("X", 100.0, Instant.now().plusSeconds(60)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
