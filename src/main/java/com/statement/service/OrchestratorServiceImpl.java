package com.statement.service;

import com.statement.model.CategorizationResult;
import com.statement.model.CategorizeResponse;
import com.statement.model.CategorizedTransaction;
import com.statement.model.Transaction;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Wires the pipeline together and owns the lifecycle of the uploaded file.
 *
 * upload -> extract (Textract) -> categorize (Bedrock) -> filter -> delete.
 *
 * The orchestrator does not trust the model's labels blindly: it groups the
 * results into the three buckets the caller asked for and passes through the
 * model's {@code missingInfo}, so ambiguous rows are visible rather than silently
 * filed. The S3 object is deleted in a {@code finally} block - the raw statement
 * is sensitive and we keep it no longer than the extraction needs it.
 */
@Service
public class OrchestratorServiceImpl implements OrchestratorService {

    private final S3StorageService storage;
    private final TextractService textract;
    private final Categorizer categorizer;

    public OrchestratorServiceImpl(S3StorageService storage,
                                   TextractService textract,
                                   Categorizer categorizer) {
        this.storage = storage;
        this.textract = textract;
        this.categorizer = categorizer;
    }

    @Override
    public CategorizeResponse categorize(byte[] pdfBytes, String originalName) {
        String key = storage.upload(pdfBytes, originalName);
        try {
            List<Transaction> transactions = textract.extract(key);
            CategorizationResult result = categorizer.categorize(transactions);

            List<CategorizedTransaction> all = result.transactions() == null ? List.of() : result.transactions();
            return new CategorizeResponse(
                    all.size(),
                    inCategory(all, "grocery"),
                    inCategory(all, "gas"),
                    inCategory(all, "restaurant"),
                    all,
                    result.missingInfo() == null ? List.of() : result.missingInfo());
        } finally {
            storage.delete(key);
        }
    }

    private List<CategorizedTransaction> inCategory(List<CategorizedTransaction> all, String category) {
        return all.stream()
                .filter(t -> category.equalsIgnoreCase(t.category()))
                .toList();
    }
}
