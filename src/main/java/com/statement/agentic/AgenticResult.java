package com.statement.agentic;

import com.statement.model.CategorizedTransaction;

import java.util.List;

/**
 * What the lead orchestrator returns:
 *   - finalAnswer: the model's final summary,
 *   - categorized: the full list of labeled transactions (from the Categorizer subagent),
 *   - trace: every turn's stop_reason and each tool_use / tool_result, so the
 *     orchestration is visible.
 */
public record AgenticResult(String finalAnswer,
                            List<CategorizedTransaction> categorized,
                            List<String> trace) {
}
