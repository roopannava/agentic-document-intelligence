package com.statement.service;

import com.statement.model.Transaction;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.BlockType;
import software.amazon.awssdk.services.textract.model.Relationship;
import software.amazon.awssdk.services.textract.model.RelationshipType;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Reconstructs Textract TABLE/CELL blocks into {@link Transaction} rows.
 *
 * Textract returns a flat list of blocks; a TABLE block points (via CHILD
 * relationships) at CELL blocks, and each CELL points at the WORD blocks that
 * make up its text. We rebuild the grid by (rowIndex, columnIndex), read the
 * header row to guess which column is date / description / amount, and map every
 * later row onto a Transaction.
 *
 * This is HEURISTIC - bank statements differ wildly. The header-keyword guess
 * handles common layouts; if headers are unrecognizable it falls back to
 * "first column = date, last = amount, the rest = description". If this proves
 * too brittle on a real statement, the documented alternative is to skip this
 * parser and hand Textract's raw text to Bedrock to structure and categorize in
 * one call. Keeping the heuristic isolated here makes that swap a one-file change.
 */
@Component
public class TextractTableParser {

    public List<Transaction> toTransactions(List<Block> blocks) {
        Map<String, Block> byId = new HashMap<>();
        for (Block b : blocks) {
            byId.put(b.id(), b);
        }

        List<Transaction> transactions = new ArrayList<>();
        for (Block block : blocks) {
            if (block.blockType() == BlockType.TABLE) {
                transactions.addAll(parseTable(block, byId));
            }
        }
        return transactions;
    }

    private List<Transaction> parseTable(Block table, Map<String, Block> byId) {
        // rowIndex -> (columnIndex -> cell text)
        TreeMap<Integer, TreeMap<Integer, String>> grid = new TreeMap<>();

        for (String cellId : childIds(table)) {
            Block cell = byId.get(cellId);
            if (cell == null || cell.blockType() != BlockType.CELL) {
                continue;
            }
            String text = cellText(cell, byId);
            grid.computeIfAbsent(cell.rowIndex(), r -> new TreeMap<>())
                    .put(cell.columnIndex(), text);
        }

        if (grid.isEmpty()) {
            return List.of();
        }

        // First row is treated as the header; use it to locate the columns.
        TreeMap<Integer, String> header = grid.firstEntry().getValue();
        int dateCol = findColumn(header, "date", "posted", "trans");
        int descCol = findColumn(header, "description", "detail", "merchant", "payee", "memo");
        int amountCol = findColumn(header, "amount", "debit", "credit", "value", "charge");

        List<Transaction> rows = new ArrayList<>();
        boolean first = true;
        for (TreeMap<Integer, String> row : grid.values()) {
            if (first) {
                first = false; // skip the header row
                continue;
            }
            int lastCol = row.lastKey();
            String date = cellAt(row, dateCol >= 0 ? dateCol : row.firstKey());
            String amount = cellAt(row, amountCol >= 0 ? amountCol : lastCol);
            String description = descCol >= 0
                    ? cellAt(row, descCol)
                    : joinBetween(row, dateCol >= 0 ? dateCol : row.firstKey(),
                                       amountCol >= 0 ? amountCol : lastCol);

            if (isBlank(date) && isBlank(description) && isBlank(amount)) {
                continue;
            }
            rows.add(new Transaction(date, description, amount));
        }
        return rows;
    }

    private int findColumn(TreeMap<Integer, String> header, String... keywords) {
        for (Map.Entry<Integer, String> e : header.entrySet()) {
            String h = e.getValue() == null ? "" : e.getValue().toLowerCase();
            for (String kw : keywords) {
                if (h.contains(kw)) {
                    return e.getKey();
                }
            }
        }
        return -1;
    }

    private String joinBetween(TreeMap<Integer, String> row, int startExclusive, int endExclusive) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<Integer, String> e : row.entrySet()) {
            int col = e.getKey();
            if (col == startExclusive || col == endExclusive) {
                continue;
            }
            if (!isBlank(e.getValue())) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(e.getValue().trim());
            }
        }
        return sb.toString();
    }

    private String cellAt(TreeMap<Integer, String> row, int col) {
        String v = row.get(col);
        return v == null ? "" : v.trim();
    }

    /** A CELL's text is its child WORD blocks joined with spaces. */
    private String cellText(Block cell, Map<String, Block> byId) {
        StringBuilder sb = new StringBuilder();
        for (String wordId : childIds(cell)) {
            Block word = byId.get(wordId);
            if (word != null && word.blockType() == BlockType.WORD && word.text() != null) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(word.text());
            }
        }
        return sb.toString();
    }

    private List<String> childIds(Block block) {
        List<String> ids = new ArrayList<>();
        if (block.relationships() != null) {
            for (Relationship rel : block.relationships()) {
                if (rel.type() == RelationshipType.CHILD) {
                    ids.addAll(rel.ids());
                }
            }
        }
        return ids;
    }

    private boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
