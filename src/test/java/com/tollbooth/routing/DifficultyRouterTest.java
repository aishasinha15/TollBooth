package com.tollbooth.routing;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Unit tests for routing: everyday questions stay cheap, reasoning questions go smart. */
class DifficultyRouterTest {

    private final DifficultyRouter router = new DifficultyRouter();

    private Difficulty route(String question) {
        return router.route(question).difficulty();
    }

    // ---------- EASY: lookups and FAQs ----------

    @Test
    void greetingIsEasy() {
        assertThat(route("hi")).isEqualTo(Difficulty.EASY);
    }

    @Test
    void faqIsEasy() {
        assertThat(route("What is your return policy?")).isEqualTo(Difficulty.EASY);
        assertThat(route("How do I track my order?")).isEqualTo(Difficulty.EASY);
        assertThat(route("What are your delivery charges?")).isEqualTo(Difficulty.EASY);
    }

    @Test
    void maskedPersonalQuestionIsEasy() {
        assertThat(route("Call me on [PHONE_1] about my refund")).isEqualTo(Difficulty.EASY);
    }

    @Test
    void singleConditionAloneStaysEasy() {
        // "under" alone is only 1 point
        assertThat(route("Is the order under my name?")).isEqualTo(Difficulty.EASY);
    }

    // ---------- HARD: planning, comparing, many conditions ----------

    @Test
    void tripPlanningIsHard() {
        RouteDecision d = router.route(
                "Plan a 4-day Goa trip for a family of 4 under ₹60,000 with a pool hotel");
        assertThat(d.difficulty()).isEqualTo(Difficulty.HARD);
        assertThat(d.reason()).contains("keyword 'plan a'", "money amount", "limit word", "number of days/people");
    }

    @Test
    void comparisonIsHard() {
        assertThat(route("Compare the Nike Pegasus and Adidas Ultraboost for running"))
                .isEqualTo(Difficulty.HARD);
        assertThat(route("iPhone vs Pixel, which camera is better?")).isEqualTo(Difficulty.HARD);
    }

    @Test
    void recommendationIsHard() {
        assertThat(route("Can you recommend a winter jacket?")).isEqualTo(Difficulty.HARD);
    }

    @Test
    void twoConditionsWithoutKeywordIsHard() {
        // money amount + limit word = 2 points
        assertThat(route("Show me sneakers under Rs 3000")).isEqualTo(Difficulty.HARD);
    }

    @Test
    void severalQuestionsAndLongTextIsHard() {
        String longQuestion = "I ordered a blue kurta last week and it arrived in the wrong size, "
                + "and I also want to know whether I can exchange it for a different colour instead "
                + "of the same one, and how long the pickup usually takes in Pune? Also, will I get "
                + "the refund on my card or as store credit?";
        assertThat(route(longQuestion)).isEqualTo(Difficulty.HARD);
    }

    @Test
    void reasonExplainsEasyChoice() {
        assertThat(router.route("hi").reason()).isEqualTo("easy (score 0): short, simple question");
    }
}
