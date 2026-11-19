package com.comet.opik.infrastructure;

import com.comet.opik.TestConfigUtils;
import io.dropwizard.util.Duration;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("inference config in the shipped config.yml")
class InferenceConfigTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private static final String OLD_URL = "https://old.example.com";
    private static final String NEW_URL = "https://new.example.com";

    static Stream<Arguments> urlEnvironments() {
        return Stream.of(
                arguments("neither name", Map.of(), null),
                arguments("only the old name", Map.of("AI_LABELS_INFERENCE_URL", OLD_URL), OLD_URL),
                arguments("only the new name", Map.of("OPIK_INFERENCE_URL", NEW_URL), NEW_URL),
                arguments("both names", Map.of("AI_LABELS_INFERENCE_URL", OLD_URL, "OPIK_INFERENCE_URL", NEW_URL),
                        NEW_URL));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("urlEnvironments")
    @DisplayName("OPIK_INFERENCE_URL falls back to AI_LABELS_INFERENCE_URL")
    void urlFallsBackToTheOldName(String description, Map<String, String> environment, String expected)
            throws Exception {
        assertThat(ShippedConfig.load(environment).getInference().getUrl()).isEqualTo(expected);
    }

    static Stream<Arguments> decidesAndConnections() {
        return Stream.of(
                arguments("the defaults", 48, 64, true),
                arguments("one below the pool", 63, 64, true),
                arguments("as many as the pool", 64, 64, false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("decidesAndConnections")
    @DisplayName("decide calls leave connections in the shared pool for the other calls")
    void decidesStayBelowTheConnectionsPerRoute(String description, int maxConcurrentDecides,
            int maxConnectionsPerRoute, boolean valid) {
        var config = TestConfigUtils.loadConfigTest();
        config.getInference().setMaxConcurrentDecides(maxConcurrentDecides);
        config.getJerseyClient().setMaxConnectionsPerRoute(maxConnectionsPerRoute);

        assertThat(VALIDATOR.validateProperty(config, "inferenceDecidesWithinConnectionPool")).hasSize(valid ? 0 : 1);
    }

    static Stream<Arguments> topicsEmbedSettings() {
        return Stream.of(
                arguments("at the limits", 256, Duration.seconds(60), List.of()),
                arguments("a batch over maxEmbedInputs", 257, Duration.seconds(60),
                        List.of("topicsEmbedBatchWithinLimits")),
                arguments("a timeout over maxEmbedDeadline", 256, Duration.milliseconds(60_001),
                        List.of("topicsEmbedTimeoutWithinLimits")));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("topicsEmbedSettings")
    @DisplayName("Topics embed calls stay within inference.limits")
    void topicsEmbedStaysWithinTheLimits(String description, int embedBatchSize, Duration embedTimeout,
            List<String> expectedViolations) {
        var config = TestConfigUtils.loadConfigTest();
        config.getTopics().getVectors().setEmbedBatchSize(embedBatchSize);
        config.getTopics().getVectors().setEmbedTimeout(embedTimeout);

        var violations = Stream.of("topicsEmbedBatchWithinLimits", "topicsEmbedTimeoutWithinLimits")
                .flatMap(property -> VALIDATOR.validateProperty(config, property).stream())
                .map(violation -> violation.getPropertyPath().toString())
                .toList();

        assertThat(violations).containsExactlyInAnyOrderElementsOf(expectedViolations);
    }
}
