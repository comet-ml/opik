package com.comet.opik.domain;

import com.comet.opik.api.error.ErrorMessage;
import jakarta.ws.rs.BadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
    @DisplayName("Many bad rows are listed up to twenty, then counted")
    void validateWhenManyProblemsListsTheFirstTwenty() {
        var badRows = IntStream.range(0, 25).mapToObj(index -> "not json").toArray(String[]::new);

        assertThatThrownBy(() -> GuardrailTrainingDataset.validate(lines(badRows)))
                .isInstanceOfSatisfying(BadRequestException.class, exception -> {
                    var errors = ((ErrorMessage) exception.getResponse().getEntity()).errors();
                    assertThat(errors).hasSize(21);
                    assertThat(errors.getFirst()).isEqualTo("not a JSON object, line '1'");
                    assertThat(errors.getLast()).isEqualTo("more problems not listed, count '8'");
                });
    }
}
