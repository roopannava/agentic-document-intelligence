package com.statement.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * The structured result the categorizer MUST produce - and, by construction, the
 * input schema of the one Bedrock tool it is given.
 *
 * This is the direct analogue of the subagent demo's {@code SubagentResult}: the
 * same record is both the Java type we parse into AND the tool contract the model
 * fills, so the return is structured by construction rather than by hope.
 *
 * <ul>
 *   <li>{@code transactions} - every input transaction, now carrying a category
 *       and a confidence.</li>
 *   <li>{@code missingInfo} - the honesty valve. A transaction the model cannot
 *       confidently place (a cryptic {@code "SQ *"} merchant, a foreign string)
 *       goes here instead of being force-fit into a wrong bucket. Empty when every
 *       row was clear.</li>
 * </ul>
 *
 * Unknown fields are ignored to stay forgiving of model output.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CategorizationResult(
        List<CategorizedTransaction> transactions,
        List<String> missingInfo) {
}
