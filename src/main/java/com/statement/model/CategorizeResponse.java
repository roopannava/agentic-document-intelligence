package com.statement.model;

import java.util.List;

/**
 * The response body for POST /api/categorize.
 *
 * It leads with the three buckets the user actually asked for - grocery, gas,
 * restaurant - then includes the full categorized list and anything the model
 * flagged as unclear. {@code transactionCount} is the number Textract extracted,
 * so the caller can sanity-check that extraction saw the whole statement.
 */
public record CategorizeResponse(
        int transactionCount,
        List<CategorizedTransaction> grocery,
        List<CategorizedTransaction> gas,
        List<CategorizedTransaction> restaurant,
        List<CategorizedTransaction> all,
        List<String> missingInfo) {
}
