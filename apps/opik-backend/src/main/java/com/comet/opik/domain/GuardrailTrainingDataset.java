package com.comet.opik.domain;

import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.io.input.BoundedInputStream;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The labelled rows a guardrail model trains on, as a member uploads them: JSON Lines in UTF-8, one
 * {@code {"text": "...", "label": "yes" | "no"}} object per line, yes meaning the text violates. The whole file is
 * checked before anything is created, so a bad file is a 400 that lists its problems and leaves nothing behind. The
 * trainer checks the same rules again.
 */
@UtilityClass
public class GuardrailTrainingDataset {

    public static final long MAX_BYTES = 50L * 1024 * 1024;
    public static final int MIN_ROWS = 20;
    public static final int MAX_ROWS = 50_000;
    public static final int MIN_ROWS_PER_LABEL = 5;
    public static final int MAX_TEXT_LENGTH = 12_000;
    private static final List<String> LABELS = List.of("yes", "no");
    private static final int MAX_PROBLEMS_LISTED = 20;

    /**
     * @throws BadRequestException listing the file's problems: the file's own first, then the first
     *                             {@value MAX_PROBLEMS_LISTED} of its lines', each with its number
     */
    public static void validate(@NonNull InputStream file) {
        // Trailing tokens fail the row: without it, a line holding a row and then more text reads as just the row.
        var rowReader = JsonUtils.getMapper().readerFor(JsonNode.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        var lineProblems = new ArrayList<String>();
        int unlistedLineProblems = 0;
        Map<String, Integer> rowsPerLabel = new TreeMap<>();
        LABELS.forEach(label -> rowsPerLabel.put(label, 0));
        int rows = 0;
        boolean notUtf8 = false;
        boolean tooLarge;

        // A reader given only the charset turns malformed bytes into U+FFFD and reads on; this decoder stops at them.
        var utf8 = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (var bounded = BoundedInputStream.builder().setInputStream(file).setMaxCount(MAX_BYTES + 1).get();
                var reader = new BufferedReader(new InputStreamReader(bounded, utf8))) {
            try {
                int lineNumber = 0;
                String line;
                while ((line = reader.readLine()) != null) {
                    lineNumber++;
                    if (line.isBlank()) {
                        continue;
                    }
                    var row = parse(rowReader, line);
                    var problem = problemOf(row);
                    if (problem == null) {
                        rows++;
                        rowsPerLabel.merge(row.get("label").asText(), 1, Integer::sum);
                    } else if (lineProblems.size() < MAX_PROBLEMS_LISTED) {
                        lineProblems.add("%s, line '%d'".formatted(problem, lineNumber));
                    } else {
                        // Only counted past the listed ones, so a file of millions of bad lines costs no memory.
                        unlistedLineProblems++;
                    }
                }
            } catch (CharacterCodingException exception) {
                notUtf8 = true;
            }
            tooLarge = bounded.getCount() > MAX_BYTES;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }

        var problems = new ArrayList<String>();
        if (tooLarge) {
            // Reading stopped at the limit, so the last row read may be cut short, even inside a character: the size
            // is the only problem.
            problems.add("the file is larger than 50 MB");
        } else if (notUtf8) {
            // Reading stopped at the bad bytes, so the rows after them were never checked.
            problems.add("the file is not valid UTF-8");
        } else {
            if (rows < MIN_ROWS || rows > MAX_ROWS) {
                problems.add("the file needs 20 to 50,000 valid rows, rows '%d'".formatted(rows));
            }
            rowsPerLabel.forEach((label, count) -> {
                if (count < MIN_ROWS_PER_LABEL) {
                    problems.add("the file needs at least 5 rows per label, label '%s', rows '%d'".formatted(label,
                            count));
                }
            });
            problems.addAll(lineProblems);
            if (unlistedLineProblems > 0) {
                problems.add("more lines with problems not listed, count '%d'".formatted(unlistedLineProblems));
            }
        }
        if (problems.isEmpty()) {
            return;
        }
        throw new BadRequestException(Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorMessage(problems))
                .build());
    }

    /** What is wrong with the row, or null when it is a valid row. */
    private static String problemOf(JsonNode row) {
        if (row == null || !row.isObject()) {
            return "not a JSON object";
        }
        var text = row.path("text");
        var label = row.path("label");
        if (!text.isTextual() || text.asText().isBlank()) {
            return "\"text\" must be a non-empty string";
        }
        // Characters are code points, as the trainer's Python len() counts them: an emoji is one, not two UTF-16 units.
        if (text.asText().codePointCount(0, text.asText().length()) > MAX_TEXT_LENGTH) {
            return "\"text\" is longer than 12,000 characters";
        }
        if (!label.isTextual() || !LABELS.contains(label.asText())) {
            return "\"label\" must be \"yes\" or \"no\"";
        }
        return null;
    }

    private static JsonNode parse(ObjectReader rowReader, String line) {
        try {
            return rowReader.readValue(line);
        } catch (IOException exception) {
            return null;
        }
    }
}
