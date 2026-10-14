package com.tollbooth.eval;

import com.tollbooth.eval.ThresholdMaths.ScoredPair;
import com.tollbooth.eval.ThresholdMaths.ThresholdRow;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ThresholdMathsTest {

    /** The real Phase 6 numbers. */
    private static final List<ScoredPair> PHASE6 = List.of(
            new ScoredPair("return policy", "send back an item", true, 0.914),
            new ScoredPair("return policy", "exchange policy", false, 0.925),
            new ScoredPair("return policy", "earlier paraphrase", true, 0.944),
            new ScoredPair("return policy", "cancel my order", false, 0.850),
            new ScoredPair("goa plan", "goa plan reworded", true, 0.987),
            new ScoredPair("return policy", "return reworded", true, 0.991));

    @Test
    void recommendsJustAboveTheHighestDifferentPair() {
        // highest DIFFERENT = 0.925 (exchange policy) -> 0.925 + 0.005 = 0.930
        assertThat(ThresholdMaths.recommendThreshold(PHASE6, 0.88)).isEqualTo(0.93);
    }

    @Test
    void roundsUpNotToTheNearest() {
        List<ScoredPair> pairs = List.of(new ScoredPair("a", "b", false, 0.9512));
        assertThat(ThresholdMaths.recommendThreshold(pairs, 0.88)).isEqualTo(0.96);
    }

    @Test
    void neverBelowTheSemanticThresholdNorAboveOne() {
        assertThat(ThresholdMaths.recommendThreshold(
                List.of(new ScoredPair("a", "b", false, 0.70)), 0.88)).isEqualTo(0.88);
        assertThat(ThresholdMaths.recommendThreshold(
                List.of(new ScoredPair("a", "b", false, 0.999)), 0.88)).isEqualTo(1.0);
    }

    @Test
    void tableCountsHitsAndWrongHits() {
        List<ThresholdRow> rows = ThresholdMaths.table(PHASE6, List.of(0.92, 0.94));

        // At 0.92: SAME 0.944, 0.987, 0.991 accepted (3 of 4) but DIFFERENT 0.925 too (1 of 2)
        assertThat(rows.get(0).sameAccepted()).isEqualTo(3);
        assertThat(rows.get(0).wrongAccepted()).isEqualTo(1);
        assertThat(rows.get(0).hitRatePercent()).isEqualTo(75.0);
        assertThat(rows.get(0).wrongHitRatePercent()).isEqualTo(50.0);
        // At 0.94: no wrong hits, still 3 of 4 SAME pairs
        assertThat(rows.get(1).wrongAccepted()).isZero();
        assertThat(rows.get(1).sameAccepted()).isEqualTo(3);
    }

    @Test
    void probabilityCandidatesRunFrom5To95Percent() {
        assertThat(ThresholdMaths.probabilityCandidates()).hasSize(19).startsWith(0.05).endsWith(0.95);
    }

    @Test
    void findsTheExtremes() {
        assertThat(ThresholdMaths.highestDifferent(PHASE6)).get().extracting(ScoredPair::b).isEqualTo("exchange policy");
        assertThat(ThresholdMaths.lowestSame(PHASE6)).get().extracting(ScoredPair::b).isEqualTo("send back an item");
    }
}
