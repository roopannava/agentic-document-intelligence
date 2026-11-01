package com.statement.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.statement.model.CategorizationResult;
import com.statement.model.Transaction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Stage 2 - categorization via the Anthropic API directly (API key), NOT Bedrock.
 *
 * Active only when {@code app.llm.provider=anthropic}. Same Claude, different door:
 * instead of AWS Bedrock + IAM, this talks to Anthropic's own endpoint with an
 * ANTHROPIC_API_KEY (read from the environment by {@code fromEnv()}).
 *
 * The "contract" is expressed with the SDK's structured-output feature: we hand it
 * {@link CategorizationResult}.class and the SDK derives the JSON schema, constrains
 * the model to it, and parses the reply straight back into the record - the same
 * "define the shape, get the shape" idea as the Bedrock tool, without a hand-built tool.
 */
@Component
@ConditionalOnProperty(name = "app.llm.provider", havingValue = "anthropic")
public class AnthropicCategorizer implements Categorizer {

    private static final String SYSTEM_PROMPT = """
            You categorize bank-statement transactions. For EACH transaction you are given,
            assign a category and a confidence. Rules:
            - category MUST be one of: grocery, gas, restaurant, other.
              Use "other" for anything that is not clearly a grocery store, fuel/gas station,
              or restaurant/cafe/food-delivery charge.
            - Never invent transactions; return exactly the ones you were given, in order.
            - If a merchant string is too cryptic to place confidently, categorize it "other",
              set confidence "low", and add a short note to missingInfo explaining what was unclear.
            - confidence MUST be one of: high, medium, low.
            """;

    private final AnthropicClient client;
    private final String modelId;
    private final ObjectMapper mapper = new ObjectMapper();

    public AnthropicCategorizer(AnthropicClient client,
                               @Value("${app.anthropic.model-id}") String modelId) {
        this.client = client;
        this.modelId = modelId;
    }

    @Override
    public CategorizationResult categorize(List<Transaction> transactions) {
        String userText = "Categorize these transactions:\n" + toJson(transactions);

        var params = MessageCreateParams.builder()
                .model(modelId)
                .maxTokens(4096L)
                .system(SYSTEM_PROMPT)
                .outputConfig(CategorizationResult.class)
                .addUserMessage(userText)
                .build();

        return client.messages().create(params).content().stream()
                .flatMap(block -> block.text().stream())
                .map(textBlock -> textBlock.text())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Anthropic returned no structured categorization result"));
    }

    private String toJson(List<Transaction> transactions) {
        try {
            return mapper.writeValueAsString(transactions);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize transactions for the model", e);
        }
    }
}
