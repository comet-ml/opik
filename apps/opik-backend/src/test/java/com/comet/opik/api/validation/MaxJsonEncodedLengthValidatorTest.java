package com.comet.opik.api.validation;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class MaxJsonEncodedLengthValidatorTest {

    private record Text(@MaxJsonEncodedLength(10) String value) {
    }

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    // The expected lengths are Python's len(json.dumps(text)), which opik-inference measures.
    static Stream<Arguments> encodedLengths() {
        return Stream.of(
                Arguments.of("an ASCII letter", "a", 3),
                Arguments.of("a slash, which stays raw", "/", 3),
                Arguments.of("a quote", "\"", 4),
                Arguments.of("a backslash", "\\", 4),
                Arguments.of("a line break", "\n", 4),
                Arguments.of("a tab", "\t", 4),
                Arguments.of("another control character", "\u0001", 8),
                Arguments.of("DEL", "\u007F", 8),
                Arguments.of("a Latin letter with an accent", "é", 8),
                Arguments.of("a Cyrillic letter", "я", 8),
                Arguments.of("a Chinese character", "中", 8),
                Arguments.of("an emoji, two UTF-16 units", "😀", 14));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("encodedLengths")
    void encodedLengthMatchesPythonJsonDumps(String scenario, String text, int expectedLength) {
        assertThat(MaxJsonEncodedLengthValidator.encodedLength(text)).isEqualTo(expectedLength);
    }

    @Test
    void textUpToTheLimitIsValidAndLongerIsNot() {
        assertThat(validator.validate(new Text("a".repeat(8)))).isEmpty();
        assertThat(validator.validate(new Text("a".repeat(9)))).hasSize(1);
        assertThat(validator.validate(new Text("я"))).isEmpty();
        assertThat(validator.validate(new Text("яя"))).hasSize(1);
        assertThat(validator.validate(new Text(null))).isEmpty();
    }
}
