package com.statement.model;

/**
 * One raw transaction as Textract pulled it off the statement - before any
 * judgment about what it is.
 *
 * {@code amount} is kept as the literal text Textract returned (e.g. {@code
 * "$42.10"}, {@code "1,234.56"}, {@code "(45.00)"} for a credit) rather than a
 * parsed number: statement formats vary wildly, and the categorizer reads the
 * raw string fine. Parse to {@code BigDecimal} later only if arithmetic is needed.
 */
public record Transaction(
        String date,
        String description,
        String amount) {
}
