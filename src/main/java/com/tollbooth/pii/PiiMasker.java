package com.tollbooth.pii;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds private data in a question and swaps it for placeholders before it goes to the AI.
 *
 *   "Refund to 4111 1111 1111 1111, mail me at priya@acme.com"
 *   -> "Refund to [CARD_1], mail me at [EMAIL_1]"
 *
 * Plain Java rules (regular expressions + the Luhn check), not AI:
 * security must be predictable and testable, never a guess.
 *
 * Order matters: emails first (they can contain digits), then cards
 * (long digit runs), then phone numbers.
 */
@Component
public class PiiMasker {

    /** name@domain.tld */
    private static final Pattern EMAIL = Pattern.compile(
            "[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /**
     * 13 to 19 digits, optionally split by single spaces or hyphens: "4111 1111 1111 1111".
     * (?<!\w) and (?!\w) mean "not glued to another letter or digit",
     * so we never grab digits from the middle of a longer code.
     * Every match must ALSO pass the Luhn check (see isLuhnValid) to count as a card.
     */
    private static final Pattern CARD = Pattern.compile(
            "(?<!\\w)(?:\\d[ -]?){12,18}\\d(?!\\w)");

    /**
     * Indian mobile numbers: 10 digits starting 6-9, with optional +91 / 91 / 0 in front
     * and an optional space or hyphen in the middle: "+91 98765 43210", "09876543210".
     */
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\w)(?:\\+91[ -]?|91[ -]?|0)?[6-9]\\d{4}[ -]?\\d{5}(?!\\w)");

    public MaskResult mask(String text) {
        Map<String, String> placeholderToValue = new LinkedHashMap<>();
        Map<String, String> valueToPlaceholder = new LinkedHashMap<>();
        Map<PiiType, Integer> counts = new EnumMap<>(PiiType.class);

        if (text == null || text.isBlank()) {
            return new MaskResult(text, placeholderToValue, counts);
        }

        String masked = text;
        masked = replace(masked, EMAIL, PiiType.EMAIL, false, placeholderToValue, valueToPlaceholder, counts);
        masked = replace(masked, CARD, PiiType.CARD, true, placeholderToValue, valueToPlaceholder, counts);
        masked = replace(masked, PHONE, PiiType.PHONE, false, placeholderToValue, valueToPlaceholder, counts);

        return new MaskResult(masked, placeholderToValue, counts);
    }

    private static String replace(String text, Pattern pattern, PiiType type, boolean requireLuhn,
                                  Map<String, String> placeholderToValue,
                                  Map<String, String> valueToPlaceholder,
                                  Map<PiiType, Integer> counts) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String value = matcher.group();
            String replacement;
            if (requireLuhn && !isLuhnValid(value)) {
                replacement = value; // looks like a card, but isn't one (e.g. an order ID): leave it
            } else {
                // The same value twice gets the same placeholder, so the AI sees it's one thing
                replacement = valueToPlaceholder.computeIfAbsent(value, v -> {
                    int n = counts.merge(type, 1, Integer::sum);
                    String placeholder = "[" + type.name() + "_" + n + "]";
                    placeholderToValue.put(placeholder, v);
                    return placeholder;
                });
            }
            // quoteReplacement: treat "$" or "\" in the text literally, not as regex syntax
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * The Luhn check: a checksum every real card number passes.
     * From the rightmost digit, double every second digit (subtract 9 if over 9),
     * add everything up; a valid number's total ends in 0.
     * About 9 in 10 random digit strings fail it, which filters out most order IDs.
     */
    static boolean isLuhnValid(String candidate) {
        String digits = candidate.replaceAll("[^0-9]", "");
        if (digits.length() < 13 || digits.length() > 19) {
            return false;
        }
        int sum = 0;
        boolean doubleIt = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
        }
        return sum % 10 == 0;
    }
}
