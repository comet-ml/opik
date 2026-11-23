package com.comet.opik.utils.slim;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.StreamWriteFeature;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.core.util.JsonGeneratorDelegate;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class TeeGeneratorTest {

    /** Broken into token writes by the delegate itself, so they reach both sides without an override. */
    private static final Set<String> ROUTED_THROUGH_TOKENS = Set.of(
            "writeObject", "writeTree", "writePOJO", "copyCurrentEvent", "copyCurrentStructure");

    private static String signature(Method method) {
        return "%s(%s)".formatted(method.getName(), Arrays.stream(method.getParameterTypes())
                .map(Class::getName)
                .collect(Collectors.joining(",")));
    }

    @Test
    @DisplayName("the tee overrides every write method of the delegate, so none reaches only one side")
    void teeOverridesEveryWrite() {
        Set<String> writes = Arrays.stream(JsonGeneratorDelegate.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .filter(method -> method.getName()
                        .matches("(write|copy|flush|close|assignCurrentValue|setCurrentValue).*"))
                .filter(method -> !ROUTED_THROUGH_TOKENS.contains(method.getName()))
                .map(TeeGeneratorTest::signature)
                .collect(Collectors.toSet());
        Set<String> overridden = Arrays.stream(TeeGenerator.class.getDeclaredMethods())
                .map(TeeGeneratorTest::signature)
                .collect(Collectors.toSet());

        assertThat(overridden).containsAll(writes);
    }

    private interface Writes {
        void to(JsonGenerator generator) throws IOException;
    }

    private static final double[] DOUBLES = {
            0d, -0d, 2.5d, 1e-300, 1e300, Double.MIN_VALUE, Double.MAX_VALUE,
            RandomUtils.secure().randomDouble() * 1e12, Double.NaN, Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY};

    private static final float[] FLOATS = {
            0f, -0f, 2.5f, 1e-30f, Float.MIN_VALUE, Float.MAX_VALUE,
            RandomUtils.secure().randomFloat() * 1e6f, Float.NaN, Float.POSITIVE_INFINITY};

    private static Stream<Arguments> numberFeatures() {
        return Stream.of(
                Arguments.of("defaults", (Writes) generator -> {
                }),
                Arguments.of("numbers as strings",
                        (Writes) generator -> generator
                                .enable(JsonWriteFeature.WRITE_NUMBERS_AS_STRINGS.mappedFeature())),
                Arguments.of("non-finite unquoted",
                        (Writes) generator -> generator.disable(JsonWriteFeature.WRITE_NAN_AS_STRINGS.mappedFeature())),
                Arguments.of("fast double writer",
                        (Writes) generator -> generator
                                .enable(StreamWriteFeature.USE_FAST_DOUBLE_WRITER.mappedFeature())));
    }

    private static String written(Writes configure, Writes writes) throws IOException {
        var out = new StringWriter();
        try (var generator = JsonUtils.getMapper().createGenerator(out)) {
            configure.to(generator);
            writes.to(generator);
        }
        return out.toString();
    }

    private static void writeNumbers(JsonGenerator generator) throws IOException {
        generator.writeStartArray();
        for (double value : DOUBLES) {
            generator.writeNumber(value);
        }
        for (float value : FLOATS) {
            generator.writeNumber(value);
        }
        generator.writeEndArray();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("numberFeatures")
    @DisplayName("the tee writes doubles and floats on both sides as each side writes them itself")
    void teeWritesNumbersAsEachSide(String name, Writes configure) throws IOException {
        String expected = written(configure, TeeGeneratorTest::writeNumbers);

        var first = new StringWriter();
        var second = new StringWriter();
        try (var firstGenerator = JsonUtils.getMapper().createGenerator(first);
                var secondGenerator = JsonUtils.getMapper().createGenerator(second)) {
            configure.to(firstGenerator);
            configure.to(secondGenerator);
            try (var tee = new TeeGenerator(firstGenerator, secondGenerator)) {
                writeNumbers(tee);
            }
        }

        assertThat(first.toString()).isEqualTo(expected);
        assertThat(second.toString()).isEqualTo(expected);
    }
}
