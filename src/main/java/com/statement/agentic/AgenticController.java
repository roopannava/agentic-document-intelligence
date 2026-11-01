package com.statement.agentic;

import com.statement.model.Transaction;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Agentic demo endpoint. Takes a JSON array of transactions and hands them to the
 * lead orchestrator, which delegates to subagents through a visible stop_reason loop.
 *
 * Runs with ONLY an ANTHROPIC_API_KEY - no S3/Textract/AWS needed - so the agent
 * mechanics can be demonstrated on their own.
 *
 *   curl -s -X POST http://localhost:8080/api/agentic/process \
 *        -H 'Content-Type: application/json' \
 *        -d '[{"date":"2026-10-03","description":"SHELL OIL 574212","amount":"-45.20"}, ...]'
 */
@RestController
@RequestMapping("/api/agentic")
public class AgenticController {

    private final LeadOrchestratorAgent lead;

    public AgenticController(LeadOrchestratorAgent lead) {
        this.lead = lead;
    }

    /** Rows in hand - runs with only an ANTHROPIC_API_KEY (no AWS). */
    @PostMapping("/process")
    public AgenticResult process(@RequestBody List<Transaction> transactions) {
        return lead.run(transactions);
    }

    /**
     * Full multi-agent pipeline from a PDF: the lead agent extracts via Textract,
     * categorizes, researches unclear merchants, and summarizes. Needs AWS creds
     * (S3 + Textract) in addition to the Anthropic key.
     *
     *   curl -s -F pdf=@sample_statement.pdf http://localhost:8080/api/agentic/process-pdf
     */
    @PostMapping("/process-pdf")
    public AgenticResult processPdf(@RequestParam("pdf") MultipartFile pdf) throws IOException {
        return lead.runFromPdf(pdf.getBytes(), pdf.getOriginalFilename());
    }
}
