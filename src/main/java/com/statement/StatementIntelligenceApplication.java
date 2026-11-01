package com.statement;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the document-intelligence demo.
 *
 * The app turns a bank-statement PDF into categorized transactions in two
 * stages, each matched to the right kind of tool:
 *
 * <ul>
 *   <li><b>Stage 1 - extraction (deterministic):</b> Amazon Textract reads the
 *       PDF and returns the transaction rows. Pulling exact dates and amounts out
 *       of a document is bookkeeping, not judgment, so a purpose-built OCR/table
 *       service does it - not an LLM.</li>
 *   <li><b>Stage 2 - categorization (judgment):</b> Claude on Amazon Bedrock
 *       decides whether each transaction is grocery, gas, restaurant or other.
 *       Deciding that {@code "SQ *JOE'S"} is a restaurant is fuzzy, world-knowledge
 *       work - exactly what an LLM is for.</li>
 * </ul>
 *
 * That split (deterministic tool for facts, LLM for judgment) is the whole point
 * of the demo.
 */
@SpringBootApplication
public class StatementIntelligenceApplication {

	public static void main(String[] args) {
		SpringApplication.run(StatementIntelligenceApplication.class, args);
	}

}
