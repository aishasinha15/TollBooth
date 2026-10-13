package com.tollbooth.cache;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

/**
 * Small maths helpers for embeddings (lists of numbers that capture meaning).
 */
public final class Vectors {

    private Vectors() {
    }

    /**
     * Cosine similarity: how closely two embeddings point in the same direction.
     *   1.0  = same meaning
     *   ~0.0 = unrelated
     *   -1.0 = opposite
     * Formula: (a . b) / (|a| x |b|)
     */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || a.length != b.length) {
            return -1;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return -1;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

    /** Packs floats into compact text for Redis: 4 bytes each, then Base64. 768 numbers = about 4 KB. */
    public static String toBase64(float[] vector) {
        ByteBuffer buffer = ByteBuffer.allocate(vector.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        for (float v : vector) {
            buffer.putFloat(v);
        }
        return Base64.getEncoder().encodeToString(buffer.array());
    }

    public static float[] fromBase64(String text) {
        ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(text)).order(ByteOrder.LITTLE_ENDIAN);
        float[] vector = new float[buffer.remaining() / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = buffer.getFloat();
        }
        return vector;
    }
}
