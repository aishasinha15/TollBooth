package com.tollbooth.cache;

import com.tollbooth.ai.AiClient;
import com.tollbooth.ai.AiProviderException;
import com.tollbooth.ai.AiResult;
import com.tollbooth.config.TollboothProperties;
import com.tollbooth.service.CostCalculator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * The Phase 6 judge: asks the cheap Gemini model a tiny YES/NO question (about $0.00004).
 * The question is phrased around the ANSWER, not the topic: "exchange policy" and "return policy"
 * share a topic but need different answers. Any failure counts as "different": never risk a wrong hit.
 */
@Component
public class GeminiQuestionJudge implements QuestionJudge {

    private static final Logger log = LoggerFactory.getLogger(GeminiQuestionJudge.class);

    private final AiClient aiClient;
    private final CostCalculator costCalculator;
    private final String model;

    public GeminiQuestionJudge(AiClient aiClient, CostCalculator costCalculator, TollboothProperties props) {
        this.aiClient = aiClient;
        this.costCalculator = costCalculator;
        this.model = props.ai().cheapModel();
    }

    @Override
    public String name() {
        return model;
    }

    @Override
    public boolean free() {
        return false;
    }

    @Override
    public SameAnswerCheck judge(String newQuestion, String cachedQuestion) {
        String prompt = "Two customer questions sent to a shopping or travel app's assistant:\n"
                + "A: \"" + QuestionNormalizer.normalize(newQuestion) + "\"\n"
                + "B: \"" + QuestionNormalizer.normalize(cachedQuestion) + "\"\n"
                + "Would one correct answer fully and accurately answer BOTH questions? "
                + "Reply with only YES or NO.";
        try {
            AiResult result = aiClient.generate(model, prompt);
            BigDecimal cost = costCalculator.cost(model, result.inputTokens(), result.outputTokens());
            String reply = result.text() == null ? "" : result.text().trim().toUpperCase(Locale.ROOT);
            boolean same = reply.startsWith("YES");
            return new SameAnswerCheck(same, false, same ? 1 : 0, cost);
        } catch (AiProviderException e) {
            log.warn("Gemini check failed, treating as different: {}", e.getMessage());
            return SameAnswerCheck.failure();
        }
    }
}
