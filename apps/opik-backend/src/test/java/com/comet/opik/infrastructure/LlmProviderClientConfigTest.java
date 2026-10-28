package com.comet.opik.infrastructure;

import io.dropwizard.configuration.ConfigurationValidationException;
import io.dropwizard.configuration.FileConfigurationSourceProvider;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.configuration.YamlConfigurationFactory;
import io.dropwizard.jackson.Jackson;
import io.dropwizard.jersey.validation.Validators;
import io.dropwizard.util.Duration;
import jakarta.validation.ConstraintViolation;
import lombok.Builder;
import org.apache.commons.text.StringSubstitutor;
import org.apache.commons.text.lookup.StringLookupFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Loads and validates the shipped config.yml as OpikApplication does, with the environment taken from a map, so the
 * shared LLM HTTP client's limits are checked where a deployment sets them.
 */
@DisplayName("LLM Provider Client Config Validation Test")
class LlmProviderClientConfigTest {

    private static final String CONFIG = "config.yml";

    /** The limits of the shared LLM HTTP client. */
    @Builder(toBuilder = true)
    private record HttpClientLimits(Integer maxThreads, Duration threadKeepAlive, Integer maxRequests,
            Integer maxRequestsPerHost) {
        static HttpClientLimits of(LlmProviderClientConfig config) {
            return new HttpClientLimits(config.getHttpClientMaxThreads(), config.getHttpClientThreadKeepAlive(),
                    config.getHttpClientMaxRequests(), config.getHttpClientMaxRequestsPerHost());
        }
    }

    private static OpikConfiguration load(Map<String, String> environment) throws Exception {
        // OpikApplication's EnvironmentVariableSubstitutor, reading a map rather than the real environment
        var substitutor = new StringSubstitutor(StringLookupFactory.INSTANCE.mapStringLookup(environment));
        substitutor.setValueDelimiter(":-");
        substitutor.setEnableUndefinedVariableException(false);
        var factory = new YamlConfigurationFactory<>(OpikConfiguration.class, Validators.newValidator(),
                Jackson.newObjectMapper(), "dw");
        return factory.build(new SubstitutingSourceProvider(new FileConfigurationSourceProvider(), substitutor),
                CONFIG);
    }

    @Test
    @DisplayName("the shipped defaults are valid")
    void shippedDefaultsAreValid() throws Exception {
        var expectedLimits = new HttpClientLimits(1024, Duration.seconds(60), 1024, 1024);

        var actualLimits = HttpClientLimits.of(load(Map.of()).getLlmProviderClient());

        assertThat(actualLimits).isEqualTo(expectedLimits);
    }

    private static Stream<Arguments> inconsistentLimitsAreRefused() {
        var fewerThreads = """
                llmProviderClient.httpClientMaxThreads must be at least llmProviderClient.httpClientMaxRequests""";
        var morePerHost = """
                llmProviderClient.httpClientMaxRequestsPerHost must be at most llmProviderClient.httpClientMaxRequests""";
        return Stream.of(
                arguments(Map.of("LLM_PROVIDER_CLIENT_MAX_THREADS", "8", "LLM_PROVIDER_CLIENT_MAX_REQUESTS", "16",
                        "LLM_PROVIDER_CLIENT_MAX_REQUESTS_PER_HOST", "16"), Set.of(fewerThreads)),
                arguments(Map.of("LLM_PROVIDER_CLIENT_MAX_THREADS", "16", "LLM_PROVIDER_CLIENT_MAX_REQUESTS", "16",
                        "LLM_PROVIDER_CLIENT_MAX_REQUESTS_PER_HOST", "32"), Set.of(morePerHost)),
                arguments(Map.of("LLM_PROVIDER_CLIENT_MAX_THREADS", "8", "LLM_PROVIDER_CLIENT_MAX_REQUESTS", "16",
                        "LLM_PROVIDER_CLIENT_MAX_REQUESTS_PER_HOST", "32"), Set.of(fewerThreads, morePerHost)));
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("limits that contradict each other stop the config from loading")
    void inconsistentLimitsAreRefused(Map<String, String> environment, Set<String> expectedMessages) {
        var actualException = catchThrowableOfType(ConfigurationValidationException.class, () -> load(environment));

        var actualMessages = actualException.getConstraintViolations().stream()
                .map(ConstraintViolation::getMessage)
                .collect(Collectors.toSet());
        assertThat(actualMessages).isEqualTo(expectedMessages);
    }

    @Test
    @DisplayName("limits equal to each other are valid")
    void equalLimitsAreValid() throws Exception {
        var expectedLimits = new HttpClientLimits(16, Duration.seconds(60), 16, 16);

        var actualLimits = HttpClientLimits.of(load(Map.of("LLM_PROVIDER_CLIENT_MAX_THREADS", "16",
                "LLM_PROVIDER_CLIENT_MAX_REQUESTS", "16", "LLM_PROVIDER_CLIENT_MAX_REQUESTS_PER_HOST", "16"))
                .getLlmProviderClient());

        assertThat(actualLimits).isEqualTo(expectedLimits);
    }
}
