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
                problems.add("the file has %d valid rows; %d to %d are needed".formatted(rows, MIN_ROWS, MAX_ROWS));
            }
            rowsPerLabel.forEach((label, count) -> {
                if (count < MIN_ROWS_PER_LABEL) {
                    problems.add("the file has %d rows labelled \"%s\"; at least %d are needed"
                            .formatted(count, label, MIN_ROWS_PER_LABEL));
                }
            });
        }
        if (!problems.isEmpty()) {
            throw new BadRequestException(Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorMessage(listed(problems)))
                    .build());
        }
    }

    /** The row's label, or null with the row's problem added when it is not a valid row. */
    private static String labelOf(String line, int lineNumber, List<String> problems) {
        var row = parse(line);
        if (row == null || !row.isObject()) {
            problems.add("line %d: not a JSON object".formatted(lineNumber));
            return null;
        }
        var text = row.path("text");
        var label = row.path("label");
        String problem = null;
        if (!text.isTextual() || text.asText().isBlank()) {
            problem = "line %d: \"text\" must be a non-empty string".formatted(lineNumber);
        } else if (text.asText().length() > MAX_TEXT_LENGTH) {
            problem = "line %d: \"text\" is longer than %,d characters".formatted(lineNumber, MAX_TEXT_LENGTH);
        } else if (!label.isTextual() || !LABELS.contains(label.asText())) {
            problem = "line %d: \"label\" must be \"yes\" or \"no\"".formatted(lineNumber);
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

    private static List<String> listed(List<String> problems) {
        if (problems.size() <= MAX_PROBLEMS_LISTED) {
            return problems;
        }
        var listed = new ArrayList<>(problems.subList(0, MAX_PROBLEMS_LISTED));
        listed.add("and %d more problems".formatted(problems.size() - MAX_PROBLEMS_LISTED));
        return listed;
    }
}
