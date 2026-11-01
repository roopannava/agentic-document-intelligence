package com.statement.agentic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.statement.model.CategorizedTransaction;
import com.statement.model.Transaction;
import com.statement.service.S3StorageService;
import com.statement.service.TextractService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LEAD ORCHESTRATOR AGENT - the multi-agent centrepiece.
 *
 * Runs the agentic loop BY HAND so the mechanics are visible:
 *   call Claude -> inspect stop_reason -> if "tool_use", dispatch, feed the
 *   tool_result back, loop -> stop when stop_reason is "end_turn".
 *
 * The lead does no work itself. Its tools are delegations of two kinds:
 *   - extract_transactions    -> {@link TextractService}     (deterministic AWS tool)
 *   - categorize_transactions -> {@link CategorizerSubagent} (a Claude subagent)
 *   - research_merchant       -> {@link ResearchSubagent}    (a Claude subagent)
 *
 * That mix is the teaching point: a tool can wrap a deterministic service
 * (Textract) OR another Claude call (a subagent). The lead decides which to
 * call and when to stop; tool_choice stays at the default (auto), since forced
 * tool choice is rejected on current Claude models.
 *
 * Two entry points:
 *   - {@link #run(List)}        rows already in hand (no AWS) - testable with just an API key.
 *   - {@link #runFromPdf(byte[], String)}  full pipeline: upload -> the agent extracts via
 *     Textract, categorizes, researches, summarizes (needs AWS creds for S3 + Textract).
 */
@Component
public class LeadOrchestratorAgent {

    private static final int MAX_TURNS = 12;

    private static final String SYSTEM = """
            You are a LEAD ORCHESTRATOR agent. You coordinate specialist subagents and services to
            process a user's bank statement. You do NOT extract or categorize yourself; you delegate
            through tools.

            Tools (not all are available in every run):
            - extract_transactions: runs Amazon Textract on the uploaded PDF and returns the raw
              transaction rows. If you were NOT already given the transactions, call this FIRST.
            - categorize_transactions: hands the transactions to the Categorizer specialist, which
              labels each one grocery/gas/restaurant/other with a confidence (high/medium/low).
            - research_merchant: hands ONE cryptic merchant string to the Research specialist, which
              returns the most likely category and why.

            Procedure:
            1. If you were not already given the transactions, call extract_transactions once.
            2. Call categorize_transactions once.
            3. For EACH transaction marked confidence "low" (usually a cryptic merchant filed as
               "other"), call research_merchant with that merchant string.
            4. Write a concise final summary: the count per category, and for anything you researched,
               the resolved best-guess category with a one-line reason.
            Do not call a tool again once you already have what you need. Keep the summary short.
            """;

    private static final String EXTRACT_TOOL = "extract_transactions";
    private static final String CATEGORIZE_TOOL = "categorize_transactions";
    private static final String RESEARCH_TOOL = "research_merchant";

    private final AnthropicClient client;
    private final TextractService textract;
    private final S3StorageService storage;
    private final CategorizerSubagent categorizer;
    private final ResearchSubagent research;
    private final String leadModel;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public LeadOrchestratorAgent(AnthropicClient client,
                                 TextractService textract,
                                 S3StorageService storage,
                                 CategorizerSubagent categorizer,
                                 ResearchSubagent research,
                                 @Value("${app.agentic.lead-model}") String leadModel) {
        this.client = client;
        this.textract = textract;
        this.storage = storage;
        this.categorizer = categorizer;
        this.research = research;
        this.leadModel = leadModel;
    }

    /** Rows already in hand - no AWS. The agent categorizes + researches. */
    public AgenticResult run(List<Transaction> transactions) {
        AtomicReference<List<Transaction>> holder = new AtomicReference<>(transactions);
        String intro = "Here are the bank-statement transactions to process:\n"
                + toJson(transactions)
                + "\n\nOrchestrate the specialists to categorize them, research any unclear "
                + "merchants, then summarize.";
        // No extract tool - the rows are already provided.
        return runLoop(intro, List.of(categorizeTool(), researchTool()), holder, null);
    }

    /** Full pipeline from a PDF: the agent extracts via Textract, then categorizes + researches. */
    public AgenticResult runFromPdf(byte[] pdfBytes, String originalName) {
        String key = storage.upload(pdfBytes, originalName);
        AtomicReference<List<Transaction>> holder = new AtomicReference<>(null);
        try {
            String intro = "A bank-statement PDF has been uploaded to secure storage. "
                    + "Extract its transactions with the extractor, categorize them, research any "
                    + "unclear merchants, then summarize.";
            return runLoop(intro, List.of(extractTool(), categorizeTool(), researchTool()), holder, key);
        } finally {
            storage.delete(key);  // the raw statement is sensitive - keep it no longer than needed
        }
    }

    // --- the agentic loop (shared by both entry points) ------------------------------------

    private AgenticResult runLoop(String intro, List<Tool> tools,
                                  AtomicReference<List<Transaction>> holder, String s3Key) {
        AtomicReference<List<CategorizedTransaction>> categorized = new AtomicReference<>(List.of());
        List<MessageParam> conversation = new ArrayList<>();
        conversation.add(MessageParam.ofUser(intro));
        List<String> trace = new ArrayList<>();
        String finalAnswer = "";

        for (int turn = 1; turn <= MAX_TURNS; turn++) {
            MessageCreateParams.Builder params = MessageCreateParams.builder()
                    .model(leadModel)
                    .maxTokens(4096L)
                    .system(SYSTEM)
                    .messages(conversation);
            for (Tool tool : tools) {
                params.addTool(tool);
            }

            Message response = client.messages().create(params.build());
            conversation.add(response.toParam());  // append the assistant turn verbatim

            StopReason stop = response.stopReason().orElse(null);
            trace.add("turn " + turn + " — stop_reason=" + (stop == null ? "?" : stop.asString()));

            if (stop != null && stop.equals(StopReason.TOOL_USE)) {
                List<ContentBlockParam> toolResults = new ArrayList<>();
                for (ContentBlock block : response.content()) {
                    if (block.toolUse().isEmpty()) {
                        continue;
                    }
                    ToolUseBlock call = block.toolUse().get();
                    String result = dispatch(call, holder, categorized, s3Key, trace);
                    toolResults.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                            .toolUseId(call.id())
                            .content(result)
                            .build()));
                }
                conversation.add(MessageParam.ofUserOfContentBlockParams(toolResults));
                continue;
            }

            // end_turn (or terminal): collect the final text and stop.
            finalAnswer = response.content().stream()
                    .flatMap(b -> b.text().stream())
                    .map(t -> t.text())
                    .reduce("", (a, b) -> a + b);
            break;
        }

        return new AgenticResult(finalAnswer, categorized.get(), trace);
    }

    /** Route one tool call to the service or subagent behind it. */
    private String dispatch(ToolUseBlock call, AtomicReference<List<Transaction>> holder,
                            AtomicReference<List<CategorizedTransaction>> categorized,
                            String s3Key, List<String> trace) {
        String name = call.name();

        if (EXTRACT_TOOL.equals(name)) {
            trace.add("  → tool_use: extract_transactions  (→ Amazon Textract)");
            List<Transaction> rows = textract.extract(s3Key);
            holder.set(rows);
            trace.add("  ← tool_result: extracted " + rows.size() + " transactions");
            return "Extracted " + rows.size() + " transactions:\n" + toJson(rows);
        }

        if (CATEGORIZE_TOOL.equals(name)) {
            trace.add("  → tool_use: categorize_transactions  (→ Categorizer subagent)");
            List<Transaction> rows = holder.get();
            if (rows == null) {
                trace.add("  ← tool_result: no transactions yet");
                return "No transactions available yet. Call extract_transactions first.";
            }
            String result = categorizer.categorize(rows);
            categorized.set(parseCategorized(result));  // capture the labeled rows for the response
            trace.add("  ← tool_result returned (" + result.length() + " chars)");
            return result;
        }

        if (RESEARCH_TOOL.equals(name)) {
            String merchant = merchantArg(call);
            trace.add("  → tool_use: research_merchant { merchant=\"" + merchant + "\" }  (→ Research subagent)");
            String result = research.research(merchant);
            trace.add("  ← tool_result returned (" + result.length() + " chars)");
            return result;
        }

        trace.add("  → tool_use: " + name + "  (unknown — ignored)");
        return "Unknown tool: " + name;
    }

    // --- tool definitions (the "contracts" the lead may call) ------------------------------

    /** Deterministic tool: runs Textract on the already-uploaded PDF. No arguments. */
    private Tool extractTool() {
        return Tool.builder()
                .name(EXTRACT_TOOL)
                .description("Extract the raw transaction rows from the uploaded bank-statement PDF using "
                        + "Amazon Textract (deterministic OCR + table extraction). Call this FIRST, before "
                        + "categorizing. No arguments.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder().build())
                        .build())
                .build();
    }

    /** Subagent tool: hands the transactions to the Categorizer specialist. No arguments. */
    private Tool categorizeTool() {
        return Tool.builder()
                .name(CATEGORIZE_TOOL)
                .description("Hand the transactions to the Categorizer specialist. Returns each labeled "
                        + "grocery/gas/restaurant/other with a confidence. No arguments.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder().build())
                        .build())
                .build();
    }

    /** Subagent tool: the model supplies the merchant string to research. */
    private Tool researchTool() {
        return Tool.builder()
                .name(RESEARCH_TOOL)
                .description("Ask the Research specialist to identify one cryptic/unfamiliar merchant string "
                        + "and suggest its most likely category. Use it for any transaction the categorizer "
                        + "marked low confidence.")
                .inputSchema(Tool.InputSchema.builder()
                        .properties(Tool.InputSchema.Properties.builder()
                                .putAdditionalProperty("merchant", JsonValue.from(Map.of(
                                        "type", "string",
                                        "description", "The raw merchant string to research")))
                                .build())
                        .required(List.of("merchant"))
                        .build())
                .build();
    }

    private String merchantArg(ToolUseBlock call) {
        try {
            Map<?, ?> input = call._input().convert(Map.class);
            Object merchant = input.get("merchant");
            return merchant == null ? "" : String.valueOf(merchant);
        } catch (Exception e) {
            return "";
        }
    }

    private String toJson(List<Transaction> transactions) {
        try {
            return mapper.writeValueAsString(transactions);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize transactions", e);
        }
    }

    /**
     * Parse the Categorizer subagent's JSON output into structured rows. Tolerant:
     * slices out the JSON array (in case the model adds any stray prose) and returns
     * an empty list rather than failing the whole request if it can't parse.
     */
    private List<CategorizedTransaction> parseCategorized(String categorizerOutput) {
        try {
            int start = categorizerOutput.indexOf('[');
            int end = categorizerOutput.lastIndexOf(']');
            if (start < 0 || end <= start) {
                return List.of();
            }
            String array = categorizerOutput.substring(start, end + 1);
            return mapper.readValue(array, new TypeReference<List<CategorizedTransaction>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }
}
