package com.statement.agentic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.statement.model.Transaction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SUBAGENT - the categorization specialist.
 *
 * A self-contained Claude call with its own system prompt and one job: label
 * every transaction. The lead orchestrator reaches it through the
 * {@code categorize_transactions} tool; it never talks to the lead's tools or
 * to the research subagent. This is the "worker" in the orchestrator-worker
 * (multi-agent) pattern.
 */
@Component
public class CategorizerSubagent {

    private static final String SYSTEM = """
            You are a categorization specialist for bank-statement transactions.
            Return a JSON array; each element: {date, description, amount, category, confidence}.
            - category MUST be one of: grocery, gas, restaurant, other.
            - confidence MUST be one of: high, medium, low.
            - Return the transactions in the order given; never invent or drop any.
            - If a merchant string is too cryptic to place, use category "other" and confidence "low".
            Output ONLY the JSON array, no prose.
            """;

    private final AnthropicClient client;
    private final String model;
    private final ObjectMapper mapper = new ObjectMapper();

    public CategorizerSubagent(AnthropicClient client,
                               @Value("${app.agentic.subagent-model}") String model) {
        this.client = client;
        this.model = model;
    }

    /** Categorize the transactions; returns a JSON-array string for the lead to reason over. */
    public String categorize(List<Transaction> transactions) {
        Message response = client.messages().create(MessageCreateParams.builder()
                .model(model)
                .maxTokens(4096L)
                .system(SYSTEM)
                .addUserMessage("Categorize these transactions:\n" + toJson(transactions))
                .build());

        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> text.text())
                .reduce("", (a, b) -> a + b);
    }

    private String toJson(List<Transaction> transactions) {
        try {
            return mapper.writeValueAsString(transactions);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize transactions", e);
        }
    }
}
