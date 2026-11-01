package com.statement.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A transaction after the LLM has judged it: the original fields plus a
 * {@code category} and the model's {@code confidence}.
 *
 * {@code category} is one of {@code grocery | gas | restaurant | other};
 * {@code confidence} is {@code high | medium | low}. A low confidence is the
 * model's signal that the merchant string was ambiguous - the orchestrator can
 * surface those for review instead of trusting the label blindly.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CategorizedTransaction(
        String date,
        String description,
        String amount,
        String category,
        String confidence) {
}
