package com.comet.opik.infrastructure.llm.openrouter;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class OpenRouterRegionTest {

    private static final String GLOBAL_URL = "https://openrouter.ai/api/v1";
    private static final String EU_URL = "https://eu.openrouter.ai/api/v1";
    private static final String US_URL = "https://us.openrouter.ai/api/v1";
    private static final String PROXY_URL = "https://llm-proxy.example.com/openrouter/api/v1";

    @Nested
    class FromUrl {

        static Stream<Arguments> knownHosts() {
            return Stream.of(
                    arguments(GLOBAL_URL, OpenRouterRegion.GLOBAL),
                    arguments("https://openrouter.ai/api/alpha/decisions", OpenRouterRegion.GLOBAL),
                    arguments(EU_URL, OpenRouterRegion.EU),
                    arguments("https://eu.openrouter.ai/api/alpha/decisions", OpenRouterRegion.EU),
                    arguments(US_URL, OpenRouterRegion.US),
                    arguments("https://EU.OpenRouter.AI/api/v1", OpenRouterRegion.EU),
                    arguments("https://eu.openrouter.ai:443/api/v1", OpenRouterRegion.EU),
                    arguments("http://us.openrouter.ai", OpenRouterRegion.US),
                    arguments("https://user@eu.openrouter.ai/api/v1", OpenRouterRegion.EU));
        }

        @ParameterizedTest(name = "{0} -> {1}")
        @MethodSource("knownHosts")
        void mapsExactOpenRouterHostToItsRegion(String url, OpenRouterRegion expected) {
            assertThat(OpenRouterRegion.fromUrl(url)).isEqualTo(expected);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {
                PROXY_URL,
                "https://api.openrouter.ai/api/v1",
                "https://eu.openrouter.ai.example.com/api/v1",
                "https://example.com/eu.openrouter.ai/api/v1",
                "https://eu.openrouter.ai@example.com/api/v1",
                "https://openrouter.ai.",
                "eu.openrouter.ai/api/v1",
                "https://eu.openrouter.ai/{model}",
                "not a url"})
        void mapsAnyOtherUrlToUnknown(String url) {
            assertThat(OpenRouterRegion.fromUrl(url)).isEqualTo(OpenRouterRegion.UNKNOWN);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = "  ")
        void mapsMissingUrlToUnknown(String url) {
            assertThat(OpenRouterRegion.fromUrl(url)).isEqualTo(OpenRouterRegion.UNKNOWN);
        }
    }

    @Nested
    class Effective {

        static Stream<Arguments> workspaceBaseUrlWins() {
            return Stream.of(
                    arguments(EU_URL, GLOBAL_URL, OpenRouterRegion.EU),
                    arguments(US_URL, EU_URL, OpenRouterRegion.US),
                    arguments(GLOBAL_URL, EU_URL, OpenRouterRegion.GLOBAL),
                    arguments(PROXY_URL, EU_URL, OpenRouterRegion.UNKNOWN));
        }

        @ParameterizedTest(name = "workspace {0}, server {1} -> {2}")
        @MethodSource("workspaceBaseUrlWins")
        void usesWorkspaceBaseUrlWhenSet(String workspaceBaseUrl, String serverUrl, OpenRouterRegion expected) {
            assertThat(OpenRouterRegion.effective(workspaceBaseUrl, serverUrl)).isEqualTo(expected);
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = "  ")
        void fallsBackToServerUrlWithoutWorkspaceBaseUrl(String workspaceBaseUrl) {
            assertThat(OpenRouterRegion.effective(workspaceBaseUrl, EU_URL)).isEqualTo(OpenRouterRegion.EU);
        }

        @Test
        void isUnknownWithNeitherUrl() {
            assertThat(OpenRouterRegion.effective(null, null)).isEqualTo(OpenRouterRegion.UNKNOWN);
        }
    }
}
