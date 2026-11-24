package com.comet.opik.domain;

import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.infrastructure.GuardrailsTrainingDatasetConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectReader;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.io.input.BoundedInputStream;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

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
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The labelled rows a guardrail model trains on, as a member uploads them: JSON Lines in UTF-8, one
 * {@code {"text": "...", "label": "yes" | "no"}} object per line, yes meaning the text violates, held to the limits
 * of {@link GuardrailsTrainingDatasetConfig}. The whole file is checked before anything is created, so a bad file is a
 * 400 that lists its problems and leaves nothing behind. The trainer checks the same rules again.
 *
 * <p>A UTF-8 byte-order mark at the start, which Notepad and some exports write, is not part of the first row. The file
 * stored for the trainer drops it too, so the trainer reads plain UTF-8.</p>
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class GuardrailTrainingDataset {

    private static final List<String> LABELS = List.of("yes", "no");
    private static final int MAX_PROBLEMS_LISTED = 20;
    private static final String BYTE_ORDER_MARK = "\uFEFF";
    // JSON escapes a character in at most 12 characters (a surrogate pair as two six-character escapes), and the keys,
    // the label and the spacing around them take far less than this.
    private static final int MAX_ESCAPED_CHARACTER_LENGTH = 12;
    private static final int MAX_ROW_OVERHEAD = 1_024;

    private final @NonNull @Config GuardrailsTrainingDatasetConfig config;

    /** A line read up to a cap: its text, or null when it was longer than the cap and was skipped. */
    private record Line(String text) {
    }

    /**
     * @throws BadRequestException listing the file's problems: the file's own first, then the first
     *                             {@value MAX_PROBLEMS_LISTED} of its lines', each with its number
     */
    public void validate(@NonNull InputStream file) {
        long maxBytes = config.getMaxFileSizeMb() * 1024L * 1024L;
        // The longest line a valid row can take. A longer one is read past without being held, so a file that is one
        // huge line costs this much memory, not its size.
        int maxLineLength = config.getMaxTextLength() * MAX_ESCAPED_CHARACTER_LENGTH + MAX_ROW_OVERHEAD;
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
        try (var bounded = BoundedInputStream.builder().setInputStream(file).setMaxCount(maxBytes + 1).get();
                var reader = new BufferedReader(new InputStreamReader(bounded, utf8))) {
            try {
                int lineNumber = 0;
                Line line;
                while ((line = readLine(reader, maxLineLength)) != null) {
                    lineNumber++;
                    String problem;
                    JsonNode row = null;
                    if (line.text() == null) {
                        problem = "the line is longer than any valid row";
                    } else {
                        var text = lineNumber == 1 && line.text().startsWith(BYTE_ORDER_MARK)
                                ? line.text().substring(BYTE_ORDER_MARK.length())
                                : line.text();
                        if (text.isBlank()) {
                            continue;
                        }
                        row = parse(rowReader, text);
                        problem = problemOf(row);
                    }
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
            tooLarge = bounded.getCount() > maxBytes;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }

        var problems = new ArrayList<String>();
        if (tooLarge) {
            // Reading stopped at the limit, so the last row read may be cut short, even inside a character: the size
            // is the only problem.
            problems.add("the file is larger than %s MB".formatted(number(config.getMaxFileSizeMb())));
        } else if (notUtf8) {
            // Reading stopped at the bad bytes, so the rows after them were never checked.
            problems.add("the file is not valid UTF-8");
        } else {
            if (rows < config.getMinRows() || rows > config.getMaxRows()) {
                problems.add("the file needs %s to %s valid rows, rows '%d'".formatted(number(config.getMinRows()),
                        number(config.getMaxRows()), rows));
            }
            rowsPerLabel.forEach((label, count) -> {
                if (count < config.getMinRowsPerLabel()) {
                    problems.add("the file needs at least %s rows per label, label '%s', rows '%d'".formatted(
                            number(config.getMinRowsPerLabel()), label, count));
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
    private String problemOf(JsonNode row) {
        if (row == null || !row.isObject()) {
            return "not a JSON object";
        }
        var text = row.path("text");
        var label = row.path("label");
        if (!text.isTextual() || text.asText().isBlank()) {
            return "\"text\" must be a non-empty string";
        }
        // Characters are code points, as the trainer's Python len() counts them: an emoji is one, not two UTF-16 units.
        if (text.asText().codePointCount(0, text.asText().length()) > config.getMaxTextLength()) {
            return "\"text\" is longer than %s characters".formatted(number(config.getMaxTextLength()));
        }
        if (!label.isTextual() || !LABELS.contains(label.asText())) {
            return "\"label\" must be \"yes\" or \"no\"";
        }
        return null;
    }

    /**
     * The next line, ended by a line feed with an optional carriage return before it, as {@link BufferedReader} reads
     * one; null at the end of the file. A line longer than maxLength is read to its end without being kept, and comes
     * back without text.
     */
    private static Line readLine(BufferedReader reader, int maxLength) throws IOException {
        var line = new StringBuilder();
        boolean tooLong = false;
        int character;
        while ((character = reader.read()) != -1 && character != '\n') {
            // One character past the cap is kept, so a line of exactly maxLength followed by '\r' still fits.
            if (line.length() <= maxLength) {
                line.append((char) character);
            } else {
                tooLong = true;
            }
        }
        if (character == -1 && line.isEmpty() && !tooLong) {
            return null;
        }
        if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r') {
            line.setLength(line.length() - 1);
        }
        return new Line(tooLong || line.length() > maxLength ? null : line.toString());
    }

    private static String number(int value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    private static JsonNode parse(ObjectReader rowReader, String line) {
        try {
            return rowReader.readValue(line);
        } catch (IOException exception) {
            return null;
        }
    }
}
