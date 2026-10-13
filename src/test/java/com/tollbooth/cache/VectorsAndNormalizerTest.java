package com.tollbooth.cache;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Unit tests for the maths and text helpers behind the cache. */
class VectorsAndNormalizerTest {

    @Test
    void identicalAndScaledVectorsHaveSimilarityOne() {
        assertThat(Vectors.cosine(new float[]{1, 2, 3}, new float[]{1, 2, 3})).isCloseTo(1.0, within(1e-9));
        assertThat(Vectors.cosine(new float[]{1, 2, 3}, new float[]{2, 4, 6})).isCloseTo(1.0, within(1e-9));
    }

    @Test
    void unrelatedAndOppositeVectors() {
        assertThat(Vectors.cosine(new float[]{1, 0}, new float[]{0, 1})).isCloseTo(0.0, within(1e-9));
        assertThat(Vectors.cosine(new float[]{1, 0}, new float[]{-1, 0})).isCloseTo(-1.0, within(1e-9));
    }

    @Test
    void mismatchedLengthsNeverMatch() {
        assertThat(Vectors.cosine(new float[]{1}, new float[]{1, 2})).isEqualTo(-1.0);
    }

    @Test
    void base64RoundTripKeepsEveryNumber() {
        float[] v = new float[768];
        for (int i = 0; i < v.length; i++) {
            v[i] = (float) Math.sin(i) * 0.05f;
        }
        String text = Vectors.toBase64(v);
        assertThat(Vectors.fromBase64(text)).containsExactly(v);
        assertThat(text).hasSize(4096); // 768 numbers x 4 bytes, Base64-encoded
    }

    @Test
    void normalizerIgnoresCaseSpacingAndPunctuation() {
        String expected = "what is your return policy";
        assertThat(QuestionNormalizer.normalize("What is your return policy ?")).isEqualTo(expected);
        assertThat(QuestionNormalizer.normalize("  what is  your RETURN policy?! ")).isEqualTo(expected);
        assertThat(QuestionNormalizer.normalize("what is your return policy")).isEqualTo(expected);
    }

    @Test
    void normalizerKeepsDifferentWordsDifferent() {
        assertThat(QuestionNormalizer.normalize("How do I cancel my order?"))
                .isNotEqualTo(QuestionNormalizer.normalize("How do I place my order?"));
    }
}
