package com.comet.opik.domain;

import com.comet.opik.api.error.ErrorMessage;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Guardrail Training Dataset")
class GuardrailTrainingDatasetTest {

    private static InputStream lines(String... lines) {
        return new ByteArrayInputStream(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
    }

    private static String rows(int count, String label) {
        return IntStream.range(0, count)
                .mapToObj(index -> "{\"text\": \"row %d\", \"label\": \"%s\"}".formatted(index, label))
                .collect(Collectors.joining("\n"));
    }

    @Test
    @DisplayName("Rows within the limits, blank lines skipped, are valid")
    void validateWhenValidPasses() {
        assertThatCode(() -> GuardrailTrainingDataset.validate(lines(rows(5, "yes"), "", rows(15, "no"), "")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("A byte-order mark at the start is not part of the first row; anywhere else it is")
    void validateWhenByteOrderMarkFirstSkipsIt() {
        assertThatCode(() -> GuardrailTrainingDataset.validate(lines("\uFEFF" + rows(5, "yes"), rows(15, "no"))))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> GuardrailTrainingDataset.validate(lines(rows(5, "yes"), rows(15, "no"),
                "\uFEFF{\"text\": \"t\", \"label\": \"no\"}")))
                .isInstanceOfSatisfying(BadRequestException.class,
                        exception -> assertThat(((ErrorMessage) exception.getResponse().getEntity()).errors())
                                .containsExactly("not a JSON object, line '21'"));
    }

    @Test
    @DisplayName("A file over 50 MB is refused for its size alone, read no further than the limit")
    void validateWhenTooLargeReportsOnlyTheSize() {
        var line = "{\"text\": \"a violating text\", \"label\": \"yes\"}\n".getBytes(StandardCharsets.UTF_8);
        // Endless rows, generated as read: the limit is what stops the reading.
        var endless = new InputStream() {
            private long position;

            @Override
            public int read() {
                return line[(int) (position++ % line.length)];
            }
        };

        assertThatThrownBy(() -> GuardrailTrainingDataset.validate(endless))
                .isInstanceOfSatisfying(BadRequestException.class,
                        exception -> assertThat(((ErrorMessage) exception.getResponse().getEntity()).errors())
                                .containsExactly("the file is larger than 50 MB"));
    }

    @Test
    @DisplayName("A file over 50 MB that the limit cuts inside a character is still refused for its size alone")
    void validateWhenTooLargeAndCutInsideACharacterReportsOnlyTheSize() {
        // Two-byte characters in 64-byte lines: the limit, a multiple of 64, ends the read on a character's first byte.
        var line = ("é".repeat(31) + "\n\n").getBytes(StandardCharsets.UTF_8);
        assertThat(GuardrailTrainingDataset.MAX_BYTES % line.length).isZero();
        var endless = new InputStream() {
            private long position;

            @Override
            public int read() {
                return line[(int) (position++ % line.length)] & 0xFF;
            }
        };

        assertThatThrownBy(() -> GuardrailTrainingDataset.validate(endless))
                .isInstanceOfSatisfying(BadRequestException.class,
                        exception -> assertThat(((ErrorMessage) exception.getResponse().getEntity()).errors())
                                .containsExactly("the file is larger than 50 MB"));
    }

    @Test
    @DisplayName("A file that is not valid UTF-8 is refused for its encoding alone")
    void validateWhenNotUtf8ReportsTheEncoding() {
        var rows = (rows(5, "yes") + "\n" + rows(15, "no") + "\n").getBytes(StandardCharsets.UTF_8);
        var latin1 = "{\"text\": \"café\", \"label\": \"no\"}".getBytes(StandardCharsets.ISO_8859_1);
        var file = new byte[rows.length + latin1.length];
        System.arraycopy(rows, 0, file, 0, rows.length);
        System.arraycopy(latin1, 0, file, rows.length, latin1.length);

        assertThatThrownBy(() -> GuardrailTrainingDataset.validate(new ByteArrayInputStream(file)))
                .isInstanceOfSatisfying(BadRequestException.class,
                        exception -> assertThat(((ErrorMessage) exception.getResponse().getEntity()).errors())
                                .containsExactly("the file is not valid UTF-8"));
    }

    @Test
    @DisplayName("A text's length is in characters, so 12,000 emoji fit and one more does not")
    void validateCountsTextLengthInCharacters() {
        var emoji = "\uD83D\uDE00";

        assertThatCode(() -> GuardrailTrainingDataset.validate(lines(rows(5, "yes"), rows(15, "no"),
                "{\"text\": \"%s\", \"label\": \"no\"}".formatted(emoji.repeat(12_000)))))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> GuardrailTrainingDataset.validate(lines(rows(5, "yes"), rows(15, "no"),
                "{\"text\": \"%s\", \"label\": \"no\"}".formatted(emoji.repeat(12_001)))))
                .isInstanceOfSatisfying(BadRequestException.class,
                        exception -> assertThat(((ErrorMessage) exception.getResponse().getEntity()).errors())
                                .containsExactly("\"text\" is longer than 12,000 characters, line '21'"));
    }

    @Test
    @DisplayName("Lines with problems are listed up to twenty, then only counted")
    void validateWhenManyProblemsListsTheFirstTwenty() {
        var badRows = IntStream.range(0, 25).mapToObj(index -> "not json").toArray(String[]::new);

        assertThatThrownBy(() -> GuardrailTrainingDataset.validate(lines(rows(5, "yes"), rows(15, "no"),
                String.join("\n", badRows))))
                .isInstanceOfSatisfying(BadRequestException.class, exception -> {
                    var errors = ((ErrorMessage) exception.getResponse().getEntity()).errors();
                    assertThat(errors).hasSize(21);
                    assertThat(errors.getFirst()).isEqualTo("not a JSON object, line '21'");
                    assertThat(errors.getLast()).isEqualTo("more lines with problems not listed, count '5'");
                });
    }

    @Test
    @DisplayName("The file's own problems come before its lines'")
    void validateWhenFileAndLineProblemsListsTheFileFirst() {
        assertThatThrownBy(() -> GuardrailTrainingDataset.validate(lines(rows(5, "yes"), "not json")))
                .isInstanceOfSatisfying(BadRequestException.class,
                        exception -> assertThat(((ErrorMessage) exception.getResponse().getEntity()).errors())
                                .containsExactly(
                                        "the file needs 20 to 50,000 valid rows, rows '5'",
                                        "the file needs at least 5 rows per label, label 'no', rows '0'",
                                        "not a JSON object, line '6'"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"text\": \"a\", \"label\": \"yes\"} {\"text\": \"b\", \"label\": \"no\"}",
            "{\"text\": \"a\", \"label\": \"yes\"} and more"})
    @DisplayName("A line with anything after its row is not a row")
    void validateWhenTrailingContentReportsTheLine(String line) {
        assertThatThrownBy(() -> GuardrailTrainingDataset.validate(lines(rows(5, "yes"), rows(15, "no"), line)))
                .isInstanceOfSatisfying(BadRequestException.class,
                        exception -> assertThat(((ErrorMessage) exception.getResponse().getEntity()).errors())
                                .containsExactly("not a JSON object, line '21'"));
    }
}
