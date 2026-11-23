package com.comet.opik.domain;

import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The labelled rows a guardrail model trains on, as a member uploads them: JSON Lines, one
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
     * @throws BadRequestException listing the file's problems, each line's with its number, the first
     *                             {@value MAX_PROBLEMS_LISTED} of them
     */
    public static void validate(@NonNull InputStream file) {
        var problems = new ArrayList<String>();
        Map<String, Integer> rowsPerLabel = new TreeMap<>();
        LABELS.forEach(label -> rowsPerLabel.put(label, 0));
        int rows = 0;
        boolean tooLarge;

        try (var bounded = BoundedInputStream.builder().setInputStream(file).setMaxCount(MAX_BYTES + 1).get();
                var reader = new BufferedReader(new InputStreamReader(bounded, StandardCharsets.UTF_8))) {
            int lineNumber = 0;
            String line;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                var label = labelOf(line, lineNumber, problems);
                if (label != null) {
                    rows++;
                    rowsPerLabel.merge(label, 1, Integer::sum);
                }
            }
            tooLarge = bounded.getCount() > MAX_BYTES;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }

        if (tooLarge) {
            // Reading stopped at the limit, so the last row read may be cut short: the size is the only problem.
            problems.clear();
            problems.add("the file is larger than 50 MB");
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
        }
        if (problems.isEmpty()) {
            return;
        }
        if (problems.size() > MAX_PROBLEMS_LISTED) {
            int unlisted = problems.size() - MAX_PROBLEMS_LISTED;
            problems.subList(MAX_PROBLEMS_LISTED, problems.size()).clear();
            problems.add("more problems not listed, count '%d'".formatted(unlisted));
        }
        throw new BadRequestException(Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorMessage(problems))
                .build());
    }

    /** The row's label, or null with the row's problem added when it is not a valid row. */
    private static String labelOf(String line, int lineNumber, List<String> problems) {
        var row = parse(line);
        if (row == null || !row.isObject()) {
            problems.add("not a JSON object, line '%d'".formatted(lineNumber));
            return null;
        }
        var text = row.path("text");
        var label = row.path("label");
        String problem = null;
        if (!text.isTextual() || text.asText().isBlank()) {
            problem = "\"text\" must be a non-empty string, line '%d'".formatted(lineNumber);
        } else if (text.asText().length() > MAX_TEXT_LENGTH) {
            problem = "\"text\" is longer than 12,000 characters, line '%d'".formatted(lineNumber);
        } else if (!label.isTextual() || !LABELS.contains(label.asText())) {
            problem = "\"label\" must be \"yes\" or \"no\", line '%d'".formatted(lineNumber);
        }
        if (problem != null) {
            problems.add(problem);
            return null;
        }
        return label.asText();
    }

    private static JsonNode parse(String line) {
        try {
            return JsonUtils.getJsonNodeFromString(line);
        } catch (UncheckedIOException exception) {
            return null;
        }
    }
}
