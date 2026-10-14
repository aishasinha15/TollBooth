package com.tollbooth.cache;

import java.util.List;

/**
 * Step 2 of "retrieve, then verify": the careful look.
 *
 * Embeddings (step 1) find cached questions that LOOK similar. A judge decides whether a
 * look-alike really needs the SAME answer. Two implementations:
 *   - DuplicateModelJudge: an open-source duplicate-question model running locally. Free.
 *   - GeminiQuestionJudge: a YES/NO question to the cheap Gemini model. Costs a little per check.
 */
public interface QuestionJudge {

    /** Shown in the bill, e.g. "duplicate model" or "gemini-3.5-flash-lite". */
    String name();

    /** True if a check costs nothing, so it is worth running for every question, easy or hard. */
    boolean free();

    SameAnswerCheck judge(String newQuestion, String cachedQuestion);

    /**
     * Judges the new question against several cached questions, results in the same order.
     * The default asks one by one; DuplicateModelJudge overrides it with a single request.
     */
    default List<SameAnswerCheck> judgeAll(String newQuestion, List<String> cachedQuestions) {
        return cachedQuestions.stream().map(cached -> judge(newQuestion, cached)).toList();
    }
}
