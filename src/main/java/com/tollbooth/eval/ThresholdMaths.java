package com.tollbooth.eval;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Pure maths for choosing a cut-off from labelled pairs (Phase 7): used both for the embedding
 * similarity (auto-accept) and for the duplicate model's score. No Spring, no Redis, no AI.
 *
 * The idea: a pair labelled DIFFERENT must never pass the cut-off (that would give a user a wrong
 * answer). So the line has to sit ABOVE the highest score any DIFFERENT pair got.
 * Every SAME pair above the line is a saving; every SAME pair below it is a missed saving.
 */
public final class ThresholdMaths {

    /** Safety gap above the highest DIFFERENT score, because a new wording may score a little higher. */
    static final BigDecimal MARGIN = new BigDecimal("0.005");

    private ThresholdMaths() {
    }

    /** One labelled pair with its measured score (similarity, or duplicate-model score). */
    public record ScoredPair(String a, String b, boolean sameAnswer, double similarity) {
    }

    /**
     * What one candidate cut-off would do.
     *
     * @param sameAccepted        SAME pairs at or above it (good: money saved)
     * @param wrongAccepted       DIFFERENT pairs at or above it (bad: wrong answers)
     * @param hitRatePercent      sameAccepted as a % of all SAME pairs
     * @param wrongHitRatePercent wrongAccepted as a % of all DIFFERENT pairs
     */
    public record ThresholdRow(double threshold, int sameAccepted, int wrongAccepted,
                               double hitRatePercent, double wrongHitRatePercent) {
    }

    public static Optional<ScoredPair> highestDifferent(List<ScoredPair> pairs) {
        return pairs.stream().filter(p -> !p.sameAnswer()).max(Comparator.comparingDouble(ScoredPair::similarity));
    }

    public static Optional<ScoredPair> lowestSame(List<ScoredPair> pairs) {
        return pairs.stream().filter(ScoredPair::sameAnswer).min(Comparator.comparingDouble(ScoredPair::similarity));
    }

    /**
     * The lowest 2-decimal threshold that is at least MARGIN above every DIFFERENT pair,
     * never below {@code floor} (the semantic threshold) and never above 1.00.
     * BigDecimal, because doubles make 0.93 * 100 = 93.00000000000001 and round it the wrong way.
     */
    public static double recommendThreshold(List<ScoredPair> pairs, double floor) {
        double recommended = highestDifferent(pairs)
                .map(p -> BigDecimal.valueOf(p.similarity()).add(MARGIN)
                        .setScale(2, RoundingMode.CEILING).doubleValue())
                .orElse(floor);
        return Math.min(1.0, Math.max(floor, recommended));
    }

    public static List<ThresholdRow> table(List<ScoredPair> pairs, List<Double> candidates) {
        long totalSame = pairs.stream().filter(ScoredPair::sameAnswer).count();
        long totalDifferent = pairs.size() - totalSame;
        return candidates.stream().map(t -> {
            int same = (int) pairs.stream().filter(p -> p.sameAnswer() && p.similarity() >= t).count();
            int wrong = (int) pairs.stream().filter(p -> !p.sameAnswer() && p.similarity() >= t).count();
            return new ThresholdRow(t, same, wrong, percent(same, totalSame), percent(wrong, totalDifferent));
        }).toList();
    }

    /** 0.85, 0.86 ... 0.99: the rows of the similarity table. */
    public static List<Double> candidates() {
        return java.util.stream.IntStream.rangeClosed(85, 99).mapToObj(i -> i / 100.0).toList();
    }

    /** 0.05, 0.10 ... 0.95: the rows of the duplicate-model table (its scores run from 0 to 1). */
    public static List<Double> probabilityCandidates() {
        return java.util.stream.IntStream.rangeClosed(1, 19).mapToObj(i -> i * 5 / 100.0).toList();
    }

    static double percent(long part, long whole) {
        return whole == 0 ? 0 : Math.round(part * 1000.0 / whole) / 10.0;
    }

    public static double round3(double value) {
        return BigDecimal.valueOf(value).setScale(3, RoundingMode.HALF_UP).doubleValue();
    }
}
