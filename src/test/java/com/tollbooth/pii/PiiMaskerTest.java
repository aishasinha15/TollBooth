package com.tollbooth.pii;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for masking. Pure Java: no Spring, no database, no Docker.
 * Each test is one tricky real-world case.
 */
class PiiMaskerTest {

    private final PiiMasker masker = new PiiMasker();

    // ---------- emails ----------

    @Test
    void masksEmail() {
        MaskResult r = masker.mask("Add priya@acme.com to my order");
        assertThat(r.maskedText()).isEqualTo("Add [EMAIL_1] to my order");
        assertThat(r.counts()).containsEntry(PiiType.EMAIL, 1);
    }

    @Test
    void masksTwoDifferentEmailsWithDifferentPlaceholders() {
        MaskResult r = masker.mask("Send it to a.b@x.in and cc c_d@y.co.uk");
        assertThat(r.maskedText()).isEqualTo("Send it to [EMAIL_1] and cc [EMAIL_2]");
    }

    @Test
    void sameEmailTwiceGetsSamePlaceholder() {
        MaskResult r = masker.mask("priya@acme.com, I repeat, priya@acme.com");
        assertThat(r.maskedText()).isEqualTo("[EMAIL_1], I repeat, [EMAIL_1]");
        assertThat(r.maskedCount()).isEqualTo(1);
    }

    @Test
    void masksEmailInsideBracketsAndPunctuation() {
        MaskResult r = masker.mask("My email (priya@acme.com).");
        assertThat(r.maskedText()).isEqualTo("My email ([EMAIL_1]).");
    }

    @Test
    void digitsInsideEmailAreNotTreatedAsPhone() {
        MaskResult r = masker.mask("Write to rahul9876543210@gmail.com");
        assertThat(r.maskedText()).isEqualTo("Write to [EMAIL_1]");
        assertThat(r.counts()).doesNotContainKey(PiiType.PHONE);
    }

    // ---------- phones ----------

    @Test
    void masksTenDigitMobile() {
        assertThat(masker.mask("Call me on 9876543210").maskedText()).isEqualTo("Call me on [PHONE_1]");
    }

    @Test
    void masksMobileWithCountryCode() {
        assertThat(masker.mask("Call +91 98765 43210 now").maskedText()).isEqualTo("Call [PHONE_1] now");
        assertThat(masker.mask("Call +919876543210").maskedText()).isEqualTo("Call [PHONE_1]");
    }

    @Test
    void masksMobileWithLeadingZero() {
        assertThat(masker.mask("Call 09876543210").maskedText()).isEqualTo("Call [PHONE_1]");
    }

    @Test
    void masksMobileWithHyphen() {
        assertThat(masker.mask("Call 98765-43210").maskedText()).isEqualTo("Call [PHONE_1]");
    }

    @Test
    void tenDigitNumberStartingWithOneToFiveIsNotAMobile() {
        MaskResult r = masker.mask("Order number 1234567890");
        assertThat(r.hasPii()).isFalse();
    }

    @Test
    void phoneDigitsGluedToLettersAreLeftAlone() {
        // An order ID like ORD9876543210 is not a phone number
        assertThat(masker.mask("Track ORD9876543210 please").hasPii()).isFalse();
    }

    // ---------- cards ----------

    @Test
    void masksValidCardWithSpaces() {
        // 4111 1111 1111 1111 is a standard test card number that passes Luhn
        MaskResult r = masker.mask("Refund to 4111 1111 1111 1111 please");
        assertThat(r.maskedText()).isEqualTo("Refund to [CARD_1] please");
    }

    @Test
    void masksValidCardWithoutSpacesOrWithHyphens() {
        assertThat(masker.mask("Card 4111111111111111").maskedText()).isEqualTo("Card [CARD_1]");
        assertThat(masker.mask("Card 5555-5555-5555-4444").maskedText()).isEqualTo("Card [CARD_1]");
    }

    @Test
    void longNumberThatFailsLuhnIsNotACard() {
        // 16 digits, but not a real card: probably an order or tracking ID
        MaskResult r = masker.mask("Where is order 1234567812345678?");
        assertThat(r.hasPii()).isFalse();
        assertThat(r.maskedText()).isEqualTo("Where is order 1234567812345678?");
    }

    @Test
    void luhnCheck() {
        assertThat(PiiMasker.isLuhnValid("4111 1111 1111 1111")).isTrue();
        assertThat(PiiMasker.isLuhnValid("5555555555554444")).isTrue();
        assertThat(PiiMasker.isLuhnValid("4111111111111112")).isFalse();
        assertThat(PiiMasker.isLuhnValid("123")).isFalse();
    }

    // ---------- things that must NOT be masked ----------

    @Test
    void leavesOrdinaryNumbersAlone() {
        String text = "Pincode 560001, OTP 482913, price Rs 4999, size 9, 2 items";
        MaskResult r = masker.mask(text);
        assertThat(r.hasPii()).isFalse();
        assertThat(r.maskedText()).isEqualTo(text);
    }

    @Test
    void noPiiMeansTextUnchanged() {
        MaskResult r = masker.mask("What is your return policy?");
        assertThat(r.hasPii()).isFalse();
        assertThat(r.maskedText()).isEqualTo("What is your return policy?");
    }

    @Test
    void handlesEmptyText() {
        assertThat(masker.mask("").hasPii()).isFalse();
    }

    // ---------- mixed + restore ----------

    @Test
    void masksEverythingInOneMessage() {
        MaskResult r = masker.mask(
                "I'm priya@acme.com, phone 9876543210, card 4111 1111 1111 1111. Refund please!");
        assertThat(r.maskedText()).isEqualTo(
                "I'm [EMAIL_1], phone [PHONE_1], card [CARD_1]. Refund please!");
        assertThat(r.maskedCount()).isEqualTo(3);
    }

    @Test
    void restorePutsRealValuesBack() {
        MaskResult r = masker.mask("Call me on 9876543210 or mail priya@acme.com");
        String aiAnswer = "Sure! We'll call [PHONE_1] and send details to [EMAIL_1].";

        assertThat(r.restore(aiAnswer))
                .isEqualTo("Sure! We'll call 9876543210 and send details to priya@acme.com.");
    }

    @Test
    void restoreHandlesRepeatedPlaceholders() {
        MaskResult r = masker.mask("My number is 9876543210");
        assertThat(r.restore("[PHONE_1] noted. We'll ring [PHONE_1] today."))
                .isEqualTo("9876543210 noted. We'll ring 9876543210 today.");
    }

    @Test
    void restoreDoesNotConfuseOneWithTen() {
        StringBuilder emails = new StringBuilder();
        for (int i = 1; i <= 10; i++) {
            emails.append("user").append(i).append("@x.com ");
        }
        MaskResult r = masker.mask(emails.toString());
        assertThat(r.restore("[EMAIL_1] and [EMAIL_10]")).isEqualTo("user1@x.com and user10@x.com");
    }
}
