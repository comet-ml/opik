package com.comet.opik.domain.mapping.otel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HostProviderResolver")
class HostProviderResolverTest {

    @Nested
    @DisplayName("Known providers with api. prefix")
    class KnownProvidersWithApiPrefix {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
            "api.cerebras.ai,   cerebras",
            "api.x.ai,          xai",
            "api.deepseek.com,  deepseek",
            "api.groq.com,      groq",
            "api.perplexity.ai, perplexity",
            "api.mistral.ai,    mistral",
            "api.ai21.com,      ai21",
        })
        void resolves_api_prefixed_host_to_canonical(String host, String expected) {
            assertThat(HostProviderResolver.resolve(host, null)).isEqualTo(expected.trim());
        }
    }

    @Nested
    @DisplayName("Known providers without api. prefix")
    class KnownProvidersWithoutApiPrefix {

        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
            "cerebras.ai, cerebras",
            "groq.com,    groq",
        })
        void resolves_bare_host_to_canonical(String host, String expected) {
            assertThat(HostProviderResolver.resolve(host, null)).isEqualTo(expected.trim());
        }
    }

    @Nested
    @DisplayName("Already canonical providers pass through unchanged")
    class CanonicalProvidersPassThrough {

        @ParameterizedTest(name = "''{0}'' unchanged")
        @ValueSource(strings = {"openai", "anthropic", "bedrock", "groq", "cerebras", "xai", "deepseek"})
        void canonical_name_is_returned_unchanged(String provider) {
            assertThat(HostProviderResolver.resolve(provider, null)).isEqualTo(provider);
        }
    }

    @Nested
    @DisplayName("Unknown or unrecognized hosts pass through unchanged")
    class UnknownHostsPassThrough {

        @ParameterizedTest(name = "''{0}'' unchanged")
        @ValueSource(strings = {
            "api.unknown-llm.com",
            "api.somevendor.io",
            "internal.company.host",
        })
        void unknown_host_is_returned_unchanged(String host) {
            assertThat(HostProviderResolver.resolve(host, null)).isEqualTo(host);
        }

        @Test
        void null_provider_returns_null() {
            assertThat(HostProviderResolver.resolve(null, null)).isNull();
        }

        @Test
        void blank_provider_returns_blank() {
            assertThat(HostProviderResolver.resolve("  ", null)).isEqualTo("  ");
        }

        @Test
        void no_dot_returns_unchanged() {
            assertThat(HostProviderResolver.resolve("justlabel", null)).isEqualTo("justlabel");
        }
    }

    @Nested
    @DisplayName("Case insensitivity")
    class CaseInsensitivity {

        @ParameterizedTest(name = "{0} -> cerebras")
        @ValueSource(strings = {"API.CEREBRAS.AI", "Api.Cerebras.Ai", "API.cerebras.AI"})
        void case_insensitive_match(String host) {
            assertThat(HostProviderResolver.resolve(host, null)).isEqualTo("cerebras");
        }
    }
}
