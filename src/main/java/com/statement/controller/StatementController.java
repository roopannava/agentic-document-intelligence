package com.statement.controller;

import com.statement.model.CategorizeResponse;
import com.statement.service.OrchestratorService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * The single endpoint: POST /api/categorize.
 *
 * Takes a bank-statement PDF as multipart form-data (the {@code pdf} part) and
 * returns the transactions categorized into grocery / gas / restaurant (plus the
 * full list and anything the model flagged as unclear). {@code @CrossOrigin} is a
 * convenience for calling the backend directly from curl or a future frontend.
 *
 * Example:
 *   curl -F pdf=@statement.pdf http://localhost:8080/api/categorize
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class StatementController {

    private final OrchestratorService orchestrator;

    public StatementController(OrchestratorService orchestrator) {
        this.orchestrator = orchestrator;
    }

    @PostMapping(value = "/categorize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CategorizeResponse categorize(@RequestParam("pdf") MultipartFile pdf) {
        try {
            return orchestrator.categorize(pdf.getBytes(), pdf.getOriginalFilename());
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the uploaded PDF", e);
        }
    }
}
