package com.statement.service;

import com.statement.model.CategorizeResponse;

/**
 * Orchestrates the two stages: PDF in, categorized transactions out.
 */
public interface OrchestratorService {

    /**
     * Runs the full pipeline on one uploaded statement.
     *
     * @param pdfBytes     the raw PDF
     * @param originalName the uploaded file name (used only to label the S3 object)
     * @return the categorized transactions, grouped for the three target buckets
     */
    CategorizeResponse categorize(byte[] pdfBytes, String originalName);
}
