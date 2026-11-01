package com.statement.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.util.UUID;

/**
 * Holds the uploaded PDF in S3 just long enough for async Textract to read it.
 *
 * Async Textract ({@code StartDocumentAnalysis}) reads the document from S3 - it
 * does not accept raw bytes the way the single-page sync API does. So the flow
 * is: upload -> analyze -> delete. We delete after processing because the raw
 * statement is sensitive and there's no reason to keep it around.
 */
@Service
public class S3StorageService {

    private final S3Client s3;
    private final String bucket;

    public S3StorageService(S3Client s3, @Value("${app.s3.bucket}") String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    /** Upload the PDF under a unique key and return that key. */
    public String upload(byte[] pdfBytes, String originalName) {
        String safeName = originalName == null ? "statement.pdf" : originalName.replaceAll("[^A-Za-z0-9._-]", "_");
        String key = "uploads/" + UUID.randomUUID() + "-" + safeName;
        s3.putObject(
                PutObjectRequest.builder()
                        .bucket(bucket)
                        .key(key)
                        .contentType("application/pdf")
                        .build(),
                RequestBody.fromBytes(pdfBytes));
        return key;
    }

    /** Delete the object once we're done extracting from it. */
    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }

    public String bucket() {
        return bucket;
    }
}
