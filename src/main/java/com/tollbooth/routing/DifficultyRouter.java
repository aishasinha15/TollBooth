package com.tollbooth.routing;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Decides whether a question is EASY (cheap model) or HARD (smart model)
 * using simple, explainable rules, not AI. Rules are free, instant and testable.
 *
 * Points:
 *   +2  a "thinking" keyword: plan, compare, recommend, itinerary, pros and cons...
 *   +1  for each kind of condition: a money amount, a limit word (under/budget/between),
 *       a count of days or people
 *   +1  longer than 25 words, +2 if longer than 50
 *   +1  more than one question mark (several questions at once)
 *
 * Score 2 or more = HARD. Everything else = EASY.
 */
@Component
public class DifficultyRouter {

    static final int HARD_THRESHOLD = 2;

    /** Phrases that usually mean "this needs reasoning", not a lookup. */
    private static final List<String> HARD_KEYWORDS = List.of(
            "compare", "comparison", " vs ", " vs.", "versus", "difference between",
            "which is better", "which one should", "pros and cons",
            "recommend", "suggest", "plan a ", "plan my", "plan for", "itinerary",
            "step by step", "analyze", "analyse", "explain why");

    private static final Pattern MONEY = Pattern.compile("(₹|\\$|\\brs\\.?\\s?|\\binr\\s?)\\s?\\d");
    private static final Pattern LIMIT_WORD = Pattern.compile("\\b(under|below|within|less than|budget|between|at most|maximum)\\b");
    private static final Pattern DAYS_OR_PEOPLE = Pattern.compile(
            "\\b\\d+[\\s-]?(day|days|night|nights|people|persons|adults|kids|children|members)\\b"
                    + "|\\bfamily of \\d+\\b");

    public RouteDecision route(String question) {
        String text = " " + (question == null ? "" : question.toLowerCase(Locale.ROOT)) + " ";
        int score = 0;
        List<String> reasons = new ArrayList<>();

        for (String keyword : HARD_KEYWORDS) {
            if (text.contains(keyword)) {
                score += 2;
                reasons.add("keyword '" + keyword.trim() + "'");
                break; // one keyword is enough to count
            }
        }

        if (MONEY.matcher(text).find()) {
            score += 1;
            reasons.add("money amount");
        }
        if (LIMIT_WORD.matcher(text).find()) {
            score += 1;
            reasons.add("limit word");
        }
        if (DAYS_OR_PEOPLE.matcher(text).find()) {
            score += 1;
            reasons.add("number of days/people");
        }

        int words = text.isBlank() ? 0 : text.trim().split("\\s+").length;
        if (words > 50) {
            score += 2;
            reasons.add(words + " words");
        } else if (words > 25) {
            score += 1;
            reasons.add(words + " words");
        }

        long questionMarks = text.chars().filter(c -> c == '?').count();
        if (questionMarks > 1) {
            score += 1;
            reasons.add(questionMarks + " questions in one");
        }

        Difficulty difficulty = score >= HARD_THRESHOLD ? Difficulty.HARD : Difficulty.EASY;
        String reason = difficulty.name().toLowerCase(Locale.ROOT) + " (score " + score + ")"
                + (reasons.isEmpty() ? ": short, simple question" : ": " + String.join(", ", reasons));
        return new RouteDecision(difficulty, score, reason);
    }
}
