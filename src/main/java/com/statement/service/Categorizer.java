package com.statement.service;

import com.statement.model.CategorizationResult;
import com.statement.model.Transaction;

import java.util.List;

/**
 * Stage 2 contract - turn raw transactions into categorized ones.
 *
 * Two implementations exist, chosen at startup by {@code app.llm.provider}:
 *   - {@link BedrockCategorizer}   (provider=bedrock)   - Claude via Amazon Bedrock, IAM auth.
 *   - {@link AnthropicCategorizer} (provider=anthropic) - Claude via the Anthropic API, API key.
 *
 * The orchestrator depends on this interface, never on a concrete class, so the
 * LLM "door" can be swapped without touching the pipeline.
 */
public interface Categorizer {

    CategorizationResult categorize(List<Transaction> transactions);
}
