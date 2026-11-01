package com.statement.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.TextNode;
import software.amazon.awssdk.core.document.Document;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridges the AWS SDK's {@link Document} (the JSON-ish union type the Bedrock
 * Converse API uses for tool input and output) and plain Java / Jackson.
 *
 * The Anthropic Java SDK hands back a typed object you can {@code .convert(...)};
 * Bedrock hands back a {@code Document}, so we convert ourselves in two
 * directions:
 *
 * <ul>
 *   <li>{@link #toDocument(Object)} - turn nested Maps/Lists/primitives into a
 *       {@code Document}, used to build the tool's JSON schema.</li>
 *   <li>{@link #convert(Document, Class)} - turn the {@code Document} the model
 *       returned (its tool input) into one of our record types.</li>
 * </ul>
 */
public final class DocumentJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DocumentJson() {
    }

    /** Build an AWS {@link Document} from Maps, Lists, Strings, Numbers, Booleans. */
    public static Document toDocument(Object value) {
        if (value == null) {
            return Document.fromNull();
        }
        if (value instanceof String s) {
            return Document.fromString(s);
        }
        if (value instanceof Boolean b) {
            return Document.fromBoolean(b);
        }
        if (value instanceof Integer i) {
            return Document.fromNumber(i);
        }
        if (value instanceof Long l) {
            return Document.fromNumber(l);
        }
        if (value instanceof Double d) {
            return Document.fromNumber(d);
        }
        if (value instanceof Number n) {
            return Document.fromNumber(n.toString());
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Document> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), toDocument(e.getValue()));
            }
            return Document.fromMap(out);
        }
        if (value instanceof List<?> list) {
            List<Document> out = new ArrayList<>();
            for (Object o : list) {
                out.add(toDocument(o));
            }
            return Document.fromList(out);
        }
        throw new IllegalArgumentException("Unsupported type for Document: " + value.getClass());
    }

    /** Parse a {@link Document} (the model's tool input) straight into one of our records. */
    public static <T> T convert(Document document, Class<T> type) {
        try {
            return MAPPER.treeToValue(toJsonNode(document), type);
        } catch (Exception e) {
            throw new IllegalStateException("Could not parse tool result into " + type.getSimpleName(), e);
        }
    }

    private static JsonNode toJsonNode(Document document) {
        if (document == null || document.isNull()) {
            return NullNode.instance;
        }
        if (document.isString()) {
            return TextNode.valueOf(document.asString());
        }
        if (document.isBoolean()) {
            return BooleanNode.valueOf(document.asBoolean());
        }
        if (document.isNumber()) {
            return MAPPER.getNodeFactory().numberNode(new BigDecimal(document.asNumber().stringValue()));
        }
        if (document.isList()) {
            ArrayNode arr = MAPPER.createArrayNode();
            for (Document d : document.asList()) {
                arr.add(toJsonNode(d));
            }
            return arr;
        }
        if (document.isMap()) {
            ObjectNode obj = MAPPER.createObjectNode();
            for (Map.Entry<String, Document> e : document.asMap().entrySet()) {
                obj.set(e.getKey(), toJsonNode(e.getValue()));
            }
            return obj;
        }
        return NullNode.instance;
    }
}
