package com.tollbooth.budget;

/** The app has spent past its hard stop for today. Becomes HTTP 503. */
public class BudgetExhaustedException extends RuntimeException {

    public BudgetExhaustedException() {
        super("Daily AI budget used up");
    }
}
