package com.comet.opik.utils;

import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.utils.ValidationUtils.CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class SentinelTranslationTest {

    @Nested
    class Outbound {

        static Stream<Arguments> epochToNull() {
            var realTime = Instant.now();
            return Stream.of(
                    arguments(Instant.EPOCH, null),
                    arguments(null, null),
                    arguments(realTime, realTime));
        }

        @ParameterizedTest(name = "epochToNull({0}) -> {1}")
        @MethodSource
        void epochToNull(Instant input, Instant expected) {
            var actual = SentinelTranslation.epochToNull(input);

            assertThat(actual).isEqualTo(expected);
        }

        static Stream<Arguments> nanToNull() {
            var realValue = RandomUtils.secure().randomDouble();
            return Stream.of(
                    arguments(Double.NaN, null),
                    arguments(null, null),
                    arguments(0.0d, 0.0d),
                    arguments(realValue, realValue),
                    arguments(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY),
                    arguments(Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY));
        }

        @ParameterizedTest(name = "nanToNull({0}) -> {1}")
        @MethodSource
        void nanToNull(Double input, Double expected) {
            var actual = SentinelTranslation.nanToNull(input);

            assertThat(actual).isEqualTo(expected);
        }

        static Stream<Arguments> emptyUuidToNull() {
            var realUUID = UUID.randomUUID().toString();
            return Stream.of(
                    arguments("  ", null),
                    arguments(CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE, null),
                    arguments(null, null),
                    arguments(realUUID, realUUID));
        }

        @ParameterizedTest(name = "emptyUuidToNull([{0}]) -> {1}")
        @MethodSource
        void emptyUuidToNull(String input, String expected) {
            var actual = SentinelTranslation.emptyUuidToNull(input);

            assertThat(actual).isEqualTo(expected);
        }

        static Stream<Arguments> emptyUuidToNullableUuid() {
            var realUUID = UUID.randomUUID();
            return Stream.of(
                    arguments("  ", null),
                    // The form the driver surfaces for a NUL-padded FixedString(36): parsing it directly would throw.
                    arguments(CLICKHOUSE_FIXED_STRING_UUID_FIELD_NULL_VALUE, null),
                    arguments(null, null),
                    arguments(realUUID.toString(), realUUID));
        }

        @ParameterizedTest(name = "emptyUuidToNullableUuid([{0}]) -> {1}")
        @MethodSource
        void emptyUuidToNullableUuid(String input, UUID expected) {
            var actual = SentinelTranslation.emptyUuidToNullableUuid(input);

            assertThat(actual).isEqualTo(expected);
        }

        @ParameterizedTest(name = "emptyUuidToNullableUuid rejects [{0}]")
        @ValueSource(strings = {"not-a-uuid", "0198c1e0-1234-7000-8000"})
        void emptyUuidToNullableUuidRejectsMalformedValue(String input) {
            assertThatThrownBy(() -> SentinelTranslation.emptyUuidToNullableUuid(input))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    class Inbound {

        static Stream<Arguments> nullToEpoch() {
            var realTime = Instant.now();
            return Stream.of(
                    arguments(null, Instant.EPOCH),
                    arguments(realTime, realTime));
        }

        @ParameterizedTest(name = "nullToEpoch({0}) -> {1}")
        @MethodSource
        void nullToEpoch(Instant input, Instant expected) {
            var actual = SentinelTranslation.nullToEpoch(input);

            assertThat(actual).isEqualTo(expected);
        }

        static Stream<Arguments> nullToNaN() {
            var realValue = RandomUtils.secure().randomDouble();
            return Stream.of(
                    arguments(null, Double.NaN),
                    arguments(realValue, realValue));
        }

        @ParameterizedTest(name = "nullToNaN({0}) -> {1}")
        @MethodSource
        void nullToNaN(Double input, Double expected) {
            var actual = SentinelTranslation.nullToNaN(input);

            assertThat(actual).isEqualTo(expected);
        }

        static Stream<Arguments> nullToEmptyUuid() {
            var realUUID = UUID.randomUUID().toString();
            return Stream.of(
                    arguments(null, ""),
                    arguments("  ", ""),
                    arguments(realUUID, realUUID));
        }

        @ParameterizedTest(name = "nullToEmptyUuid({0}) -> [{1}]")
        @MethodSource
        void nullToEmptyUuid(String input, String expected) {
            var actual = SentinelTranslation.nullToEmptyUuid(input);

            assertThat(actual).isEqualTo(expected);
        }

        static Stream<Arguments> nullToEmptyUuidFromUuid() {
            var realUUID = UUID.randomUUID();
            return Stream.of(
                    arguments(null, ""),
                    arguments(realUUID, realUUID.toString()));
        }

        @ParameterizedTest(name = "nullToEmptyUuid(UUID {0}) -> [{1}]")
        @MethodSource
        void nullToEmptyUuidFromUuid(UUID input, String expected) {
            var actual = SentinelTranslation.nullToEmptyUuid(input);

            assertThat(actual).isEqualTo(expected);
        }
    }
}
