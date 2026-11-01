package com.statement.service;

import com.statement.model.Transaction;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.DocumentLocation;
import software.amazon.awssdk.services.textract.model.FeatureType;
import software.amazon.awssdk.services.textract.model.GetDocumentAnalysisRequest;
import software.amazon.awssdk.services.textract.model.GetDocumentAnalysisResponse;
import software.amazon.awssdk.services.textract.model.JobStatus;
import software.amazon.awssdk.services.textract.model.S3Object;
import software.amazon.awssdk.services.textract.model.StartDocumentAnalysisRequest;
import software.amazon.awssdk.services.textract.model.StartDocumentAnalysisResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 1 - extraction. Deterministic, not an LLM.
 *
 * Uses Textract's ASYNChronous document-analysis API with the {@code TABLES}
 * feature. Async is required because real statements are multi-page, and the
 * async path reads the PDF from S3 (hence {@link S3StorageService} runs first).
 *
 * The flow is the standard async pattern: start a job, poll until it succeeds,
 * then page through all the result blocks. {@link TextractTableParser} turns
 * those blocks into {@link Transaction} rows.
 */
@Service
public class TextractService {

    private static final int MAX_POLLS = 60;
    private static final long POLL_INTERVAL_MS = 2_000L;

    private final TextractClient textract;
    private final String bucket;
    private final TextractTableParser parser;

    public TextractService(TextractClient textract,
                           @Value("${app.s3.bucket}") String bucket,
                           TextractTableParser parser) {
        this.textract = textract;
        this.bucket = bucket;
        this.parser = parser;
    }

    /** Extract transaction rows from the PDF already stored at {@code s3Key}. */
    public List<Transaction> extract(String s3Key) {
        StartDocumentAnalysisResponse started = textract.startDocumentAnalysis(
                StartDocumentAnalysisRequest.builder()
                        .documentLocation(DocumentLocation.builder()
                                .s3Object(S3Object.builder().bucket(bucket).name(s3Key).build())
                                .build())
                        .featureTypes(FeatureType.TABLES)
                        .build());

        String jobId = started.jobId();
        List<Block> blocks = awaitBlocks(jobId);
        return parser.toTransactions(blocks);
    }

    /** Poll until the job succeeds, then collect every block across all result pages. */
    private List<Block> awaitBlocks(String jobId) {
        for (int attempt = 0; attempt < MAX_POLLS; attempt++) {
            GetDocumentAnalysisResponse page = textract.getDocumentAnalysis(
                    GetDocumentAnalysisRequest.builder().jobId(jobId).build());
            JobStatus status = page.jobStatus();

            if (status == JobStatus.SUCCEEDED) {
                List<Block> blocks = new ArrayList<>(page.blocks());
                String next = page.nextToken();
                while (next != null) {
                    GetDocumentAnalysisResponse more = textract.getDocumentAnalysis(
                            GetDocumentAnalysisRequest.builder().jobId(jobId).nextToken(next).build());
                    blocks.addAll(more.blocks());
                    next = more.nextToken();
                }
                return blocks;
            }
            if (status == JobStatus.FAILED) {
                throw new IllegalStateException("Textract analysis failed: " + page.statusMessage());
            }
            sleep();
        }
        throw new IllegalStateException("Textract analysis did not complete within "
                + (MAX_POLLS * POLL_INTERVAL_MS / 1000) + "s for job " + jobId);
    }

    private void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Textract", e);
        }
    }
}
