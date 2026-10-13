package com.tollbooth.routing;

/**
 * The router's verdict, with a human-readable reason that gets saved in the bill,
 * e.g. "hard (score 5): keyword 'plan', money amount, limit word, number of days/people".
 */
public record RouteDecision(Difficulty difficulty, int score, String reason) {
}
