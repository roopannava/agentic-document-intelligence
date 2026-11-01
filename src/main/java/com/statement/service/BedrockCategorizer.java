package com.statement.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.statement.model.CategorizationResult;
import com.statement.model.Transaction;
import com.statement.util.DocumentJson;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.document.Document;
import software.amazon.awssdk.services.bedrockruntime.BedrockRuntimeClient;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.Message;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.Tool;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;

import java.util.List;
import java.util.Map;

/**
 * Stage 2 - categorization. The LLM, doing the judgment.
 *
 * Direct analogue of the subagent demo's {@code SubagentClient}, but calling
 * Claude through Bedrock's Converse API instead of the Anthropic SDK. The model
 * is given exactly one tool, {@code submit_categorization}, whose input schema
 * mirrors {@link CategorizationResult}, and is told to answer only by calling it.
 * We parse the tool input straight into the record - structured by construction.
 *
 * As in the subagent demo, we do NOT force the tool with a tool choice; a single
 * tool plus a strict instruction gets the call, and if the model ever answers
 * with prose instead, {@link #categorize} throws and the caller can retry.
 */
@Component
@ConditionalOnProperty(name = "app.llm.provider", havingValue = "bedrock", matchIfMissing = true)
public class BedrockCategorizer implements Categorizer {

    private static final String RETURN_TOOL = "submit_categorization";

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

            You MUST answer only by calling the submit_categorization tool exactly once.
            Do not reply with plain text.
            """;

    private final BedrockRuntimeClient bedrock;
    private final String modelId;
    private final ObjectMapper mapper = new ObjectMapper();

    public BedrockCategorizer(BedrockRuntimeClient bedrock,
                              @Value("${app.bedrock.model-id}") String modelId) {
        this.bedrock = bedrock;
        this.modelId = modelId;
    }

    @Override
    public CategorizationResult categorize(List<Transaction> transactions) {
        String userText = "Categorize these transactions:\n" + toJson(transactions);

        ConverseResponse response = bedrock.converse(ConverseRequest.builder()
                .modelId(modelId)
                .system(SystemContentBlock.fromText(SYSTEM_PROMPT))
                .messages(Message.builder()
                        .role(ConversationRole.USER)
                        .content(ContentBlock.fromText(userText))
                        .build())
                .toolConfig(ToolConfiguration.builder().tools(returnTool()).build())
                .inferenceConfig(InferenceConfiguration.builder().maxTokens(4096).build())
                .build());

        for (ContentBlock block : response.output().message().content()) {
            if (block.toolUse() != null) {
                // The tool input IS the agreed shape -- parse it straight into the record.
                return DocumentJson.convert(block.toolUse().input(), CategorizationResult.class);
            }
        }
        throw new IllegalStateException(
                "Categorizer returned no tool call; expected a " + RETURN_TOOL + " result");
    }

    /** The one tool the model may call; its JSON schema mirrors {@link CategorizationResult}. */
    private Tool returnTool() {
        Map<String, Object> txnProps = Map.of(
                "date", Map.of("type", "string"),
                "description", Map.of("type", "string"),
                "amount", Map.of("type", "string"),
                "category", Map.of(
                        "type", "string",
                        "enum", List.of("grocery", "gas", "restaurant", "other")),
                "confidence", Map.of(
                        "type", "string",
                        "enum", List.of("high", "medium", "low")));

        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "transactions", Map.of(
                                "type", "array",
                                "items", Map.of(
                                        "type", "object",
                                        "properties", txnProps,
                                        "required", List.of("date", "description", "amount", "category", "confidence"))),
                        "missingInfo", Map.of(
                                "type", "array",
                                "items", Map.of("type", "string"),
                                "description", "Transactions too cryptic to place confidently. Empty if all were clear.")),
                "required", List.of("transactions", "missingInfo"));

        Document schemaDocument = DocumentJson.toDocument(schema);

        return Tool.fromToolSpec(ToolSpecification.builder()
                .name(RETURN_TOOL)
                .description("Return the categorized transactions in the agreed shape. Call this exactly once.")
                .inputSchema(ToolInputSchema.fromJson(schemaDocument))
                .build());
    }

    private String toJson(List<Transaction> transactions) {
        try {
            return mapper.writeValueAsString(transactions);
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialize transactions for the model", e);
        }
    }
}
