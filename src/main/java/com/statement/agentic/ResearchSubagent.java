package com.statement.agentic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * SUBAGENT - the merchant-research specialist.
 *
 * Given one cryptic merchant string, it returns a best-guess category and a
 * one-line reason. The lead orchestrator calls it (via the {@code research_merchant}
 * tool) only for the transactions the categorizer marked low-confidence - that
 * decision to escalate is what makes the lead an *agent* rather than a fixed
 * pipeline.
 */
@Component
public class ResearchSubagent {

    private static final String SYSTEM = """
            You are a merchant-research specialist. Given a cryptic or unfamiliar
            merchant string from a bank statement, infer the single most likely
            category (grocery, gas, restaurant, or other) and give a one-sentence
            reason. If you genuinely cannot tell, say "other" and say why.
            Answer in one or two sentences - no lists, no preamble.
            """;

    private final AnthropicClient client;
    private final String model;

    public ResearchSubagent(AnthropicClient client,
                            @Value("${app.agentic.subagent-model}") String model) {
        this.client = client;
        this.model = model;
    }

    /** Research one merchant string; returns a short natural-language finding. */
    public String research(String merchant) {
        Message response = client.messages().create(MessageCreateParams.builder()
                .model(model)
                .maxTokens(1024L)
                .system(SYSTEM)
                .addUserMessage("Merchant string: \"" + merchant + "\"\n"
                        + "What is the most likely category and why?")
                .build());

        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(text -> text.text())
                .reduce("", (a, b) -> a + b);
    }
}
