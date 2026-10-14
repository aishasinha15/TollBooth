package com.tollbooth.eval;

/**
 * One labelled example from src/main/resources/eval/cache-pairs.json.
 *
 * @param sameAnswer the human label: would ONE correct answer fully answer both questions?
 * @param note       what makes the pair interesting, e.g. "return vs exchange"
 */
public record CachePair(String a, String b, boolean sameAnswer, String note) {
}
