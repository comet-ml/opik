package com.comet.opik.api.resources.v1.events.webhooks.common;

import com.comet.opik.api.AlertEventType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class AlertWebhookUtilsTest {

    private static final String WORKSPACE = "my-workspace";
    private static final String DEFAULT_BASE = "http://localhost:5173";

    @Nested
    class ResolveBaseUrl {

        static Stream<Map<String, String>> missingOrBlankCases() {
            return Stream.of(
                    Map.of(),
                    mapWith(AlertWebhookUtils.BASE_URL_METADATA_KEY, null),
                    mapWith(AlertWebhookUtils.BASE_URL_METADATA_KEY, "   "),
                    mapWith(AlertWebhookUtils.BASE_URL_METADATA_KEY, ""));
        }

        private static Map<String, String> mapWith(String key, String value) {
            Map<String, String> map = new HashMap<>();
            map.put(key, value);
            return map;
        }

        @ParameterizedTest
        @MethodSource("missingOrBlankCases")
        void fallsBackToDefaultWhenMissingNullOrBlank(Map<String, String> metadata) {
            assertThat(AlertWebhookUtils.resolveBaseUrl(metadata, WORKSPACE))
                    .isEqualTo(DEFAULT_BASE + "/" + WORKSPACE);
        }

        static Stream<String> configuredBaseUrls() {
            return Stream.of(
                    "https://opik.example.com",
                    "https://opik.example.com/",
                    "https://opik.example.com//");
        }

        @ParameterizedTest
        @MethodSource("configuredBaseUrls")
        void guaranteesSingleSlashBeforeWorkspace(String configuredBaseUrl) {
            Map<String, String> metadata = Map.of(AlertWebhookUtils.BASE_URL_METADATA_KEY, configuredBaseUrl);

            assertThat(AlertWebhookUtils.resolveBaseUrl(metadata, WORKSPACE))
                    .isEqualTo("https://opik.example.com/" + WORKSPACE);
        }
    }

    @Nested
    class FormatWindowDuration {

        static Stream<Arguments> durations() {
            return Stream.of(
                    arguments(0, "0 seconds"),
                    arguments(1, "1 second"),
                    arguments(59, "59 seconds"),
                    arguments(60, "1 minute"),
                    arguments(120, "2 minutes"),
                    arguments(3_600, "1 hour"),
                    arguments(7_200, "2 hours"),
                    arguments(86_400, "1 day"),
                    arguments(172_800, "2 days"));
        }

        @ParameterizedTest
        @MethodSource("durations")
        void formatsEachUnitAndPluralization(long seconds, String expected) {
            assertThat(AlertWebhookUtils.formatWindowDuration(seconds)).isEqualTo(expected);
        }
    }

    @Nested
    class FormatEventType {

        static Stream<Arguments> eventTypes() {
            return Stream.of(
                    arguments(AlertEventType.PROMPT_CREATED, "Prompt Created"),
                    arguments(AlertEventType.PROMPT_DELETED, "Prompt Deleted"),
                    arguments(AlertEventType.PROMPT_COMMITTED, "Prompt Committed"),
                    arguments(AlertEventType.TRACE_ERRORS, "Trace Error Alert"),
                    arguments(AlertEventType.TRACE_FEEDBACK_SCORE, "Trace Feedback Score"),
                    arguments(AlertEventType.TRACE_THREAD_FEEDBACK_SCORE, "Thread Feedback Score"),
                    arguments(AlertEventType.TRACE_GUARDRAILS_TRIGGERED, "Guardrail Triggered"),
                    arguments(AlertEventType.EXPERIMENT_FINISHED, "Experiment Finished"),
                    arguments(AlertEventType.TRACE_COST, "Cost Alert"),
                    arguments(AlertEventType.TRACE_LATENCY, "Latency Alert"));
        }

        @ParameterizedTest
        @MethodSource("eventTypes")
        void formatsEveryEventType(AlertEventType eventType, String expected) {
            assertThat(AlertWebhookUtils.formatEventType(eventType)).isEqualTo(expected);
        }
    }
}
