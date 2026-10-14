package com.tollbooth.eval;

import com.tollbooth.routing.Difficulty;

import java.math.BigDecimal;
import java.util.List;

/**
 * What POST /v1/eval/cache-threshold returns (Phase 7).
 *
 * @param conclusions     plain-English findings, read these first
 * @param similarity      what embedding similarity alone can and can't separate
 * @param duplicateModel  how well the local duplicate-question model judged the pairs
 * @param geminiChecker   how well the Gemini YES/NO check judged them (null unless runGeminiCheck=true)
 * @param currentPolicy   what the cache would do with these pairs under the settings in application.yml
 * @param similarityTable what each possible auto-accept line would do (hits vs wrong hits)
 * @param pairs           every pair with its scores and outcome, most similar first
 */
public record CacheEvalReport(
        List<String> conclusions,
        Similarity similarity,
        DuplicateModel duplicateModel,
        GeminiChecker geminiChecker,
        Policy currentPolicy,
        List<ThresholdMaths.ThresholdRow> similarityTable,
        List<PairResult> pairs) {

    public record PairScore(String a, String b, double score) {
    }

    /**
     * @param recommendedAutoAcceptThreshold the lowest line with no wrong hits (often too high to be useful)
     * @param pairsReachingTheJudge          pairs at or above semantic-threshold: the ones a judge would see
     */
    public record Similarity(int pairs, int samePairs, int differentPairs,
                             PairScore highestDifferent, PairScore lowestSame,
                             double recommendedAutoAcceptThreshold,
                             int sameBelowSemanticThreshold, int pairsReachingTheJudge) {
    }

    /**
     * Scored on the pairs that would reach the judge (similarity >= semantic-threshold).
     *
     * @param table                rows 0.05..0.95: SAME vs DIFFERENT pairs at or above each cut-off
     * @param recommendedThreshold the lowest cut-off with no wrong hits on these pairs
     * @param modelMs              time the model took to score all 50 pairs
     */
    public record DuplicateModel(boolean available, String error, String model,
                                 double currentThreshold, int pairsJudged, int correct, double accuracyPercent,
                                 List<String> mistakes,
                                 PairScore highestDifferent, PairScore lowestSame,
                                 double recommendedThreshold, List<ThresholdMaths.ThresholdRow> table,
                                 long modelMs) {
    }

    public record GeminiChecker(int checked, int correct, double accuracyPercent, int failed,
                                List<String> mistakes, BigDecimal costUsd) {
    }

    /**
     * @param correctHits   SAME pairs answered from the saved answer (money saved)
     * @param wrongHits     DIFFERENT pairs answered with the wrong saved answer (must be 0)
     * @param missedSavings SAME pairs answered fresh (costs money, harmless)
     * @param correctMisses DIFFERENT pairs answered fresh (right)
     * @param notChecked    pairs whose outcome depends on a check that did not run
     */
    public record Policy(double semanticThreshold, double autoAcceptThreshold, String judge,
                         int correctHits, int wrongHits, int missedSavings, int correctMisses, int notChecked) {
    }

    /**
     * @param zone           at the current settings: AUTO_ACCEPT, CHECK or MISS
     * @param duplicateScore the duplicate model's score, 0 (different) to 1 (same); null if not reachable
     * @param geminiCheck    YES / NO / FAILED, or null when no Gemini check ran for this pair
     * @param outcome        CORRECT_HIT, WRONG_HIT, MISSED_SAVING, CORRECT_MISS or NEEDS_CHECK
     */
    public record PairResult(String a, String b, String label, String note, double similarity,
                             Difficulty difficulty, String zone, Double duplicateScore, String geminiCheck,
                             String outcome) {
    }
}
