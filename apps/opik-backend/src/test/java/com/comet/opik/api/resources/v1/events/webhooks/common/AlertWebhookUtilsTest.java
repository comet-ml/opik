package com.comet.opik.api.resources.v1.events.webhooks.common;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

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

        @Test
        void appendsTrailingSlashWhenMissing() {
            Map<String, String> metadata = Map.of(AlertWebhookUtils.BASE_URL_METADATA_KEY, "https://opik.example.com");

            assertThat(AlertWebhookUtils.resolveBaseUrl(metadata, WORKSPACE))
                    .isEqualTo("https://opik.example.com/" + WORKSPACE);
        }

        @Test
        void preservesSingleTrailingSlash() {
            Map<String, String> metadata = Map.of(AlertWebhookUtils.BASE_URL_METADATA_KEY, "https://opik.example.com/");

            assertThat(AlertWebhookUtils.resolveBaseUrl(metadata, WORKSPACE))
                    .isEqualTo("https://opik.example.com/" + WORKSPACE);
        }
    }
}
