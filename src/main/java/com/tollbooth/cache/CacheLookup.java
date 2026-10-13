package com.tollbooth.cache;

import com.tollbooth.domain.CacheResult;

import java.math.BigDecimal;

/**
 * The result of checking the cache.
 *
 * @param result     HIT_EXACT, HIT_SEMANTIC or MISS
 * @param answer     the saved answer on a hit, null on a miss
 * @param similarity how close the best match was (1.0 for exact hits, 0 if nothing compared)
 * @param embedding  the new question's embedding, kept so saving after a miss
 *                   doesn't need a second call to Gemini (null if not computed)
 * @param note       what the cache decided, for the bill, e.g. "confirmed by gemini-3.5-flash-lite"
 * @param checkCost  cost of the grey-zone YES/NO check, if one ran (otherwise zero)
 */
public record CacheLookup(CacheResult result, CachedAnswer answer, double similarity, float[] embedding,
                          String note, BigDecimal checkCost) {

    public static CacheLookup miss(float[] embedding) {
        return new CacheLookup(CacheResult.MISS, null, 0, embedding, null, BigDecimal.ZERO);
    }

    public boolean isHit() {
        return result != CacheResult.MISS;
    }
}
