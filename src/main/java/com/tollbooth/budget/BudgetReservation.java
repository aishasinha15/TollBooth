package com.tollbooth.budget;

/**
 * The result of reserving money before an AI call.
 *
 * @param key             the Redis counter it was taken from (null if Redis was down)
 * @param allowed         false = the app is past its hard stop for today
 * @param smartAllowed    true = the smart model still fits in the budget
 * @param reservedMicros  the estimate held, in micro-dollars
 * @param spentMicros     today's total spend including this reservation
 */
public record BudgetReservation(String key, boolean allowed, boolean smartAllowed,
                                long reservedMicros, long spentMicros) {

    /** Used when Redis is unreachable: let the request through, track nothing ("fail open"). */
    public static BudgetReservation untracked(boolean wantsSmart) {
        return new BudgetReservation(null, true, wantsSmart, 0, 0);
    }
}
