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

    static Stream<Arguments> decideCapEnvironments() {
        return Stream.of(
                arguments("neither name", Map.of(), 48),
                arguments("only the old name", Map.of("AI_LABELS_INFERENCE_MAX_CONCURRENT_DECIDES", "32"), 32),
                arguments("only the new name", Map.of("OPIK_INFERENCE_MAX_CONCURRENT_DECIDES", "40"), 40),
                arguments("both names", Map.of("AI_LABELS_INFERENCE_MAX_CONCURRENT_DECIDES", "32",
                        "OPIK_INFERENCE_MAX_CONCURRENT_DECIDES", "40"), 40));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("decideCapEnvironments")
    @DisplayName("OPIK_INFERENCE_MAX_CONCURRENT_DECIDES falls back to AI_LABELS_INFERENCE_MAX_CONCURRENT_DECIDES")
    void decideCapFallsBackToTheOldName(String description, Map<String, String> environment, int expected)
            throws Exception {
        assertThat(ShippedConfig.load(environment).getInference().getMaxConcurrentDecides()).isEqualTo(expected);
    }

    static Stream<Arguments> decidesAndConnections() {
        return Stream.of(
                arguments("the defaults", true, 48, 4, 1, 4, 64, true),
                arguments("all callers one below the pool", true, 54, 4, 1, 4, 64, true),
                arguments("all callers as many as the pool", true, 55, 4, 1, 4, 64, false),
                arguments("decides alone below the pool, with loads and embeds over it", true, 48, 12, 4, 4, 64,
                        false),
                arguments("generates fill the pool", true, 48, 4, 1, 11, 64, false),
                arguments("a cap near Integer.MAX_VALUE", true, Integer.MAX_VALUE, 4, 1, 4, 64, false),
                arguments("guardrails off", false, 256, 64, 8, 64, 64, true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("decidesAndConnections")
    @DisplayName("calls to opik-inference stay below the shared pool's connections per route")
    void callsStayBelowTheConnectionsPerRoute(String description, boolean guardrailsEnabled, int maxConcurrentDecides,
            int loadConcurrency, int embedConcurrency, int maxConcurrentGenerates, int maxConnectionsPerRoute,
            boolean valid) {
        var config = TestConfigUtils.loadConfigTest();
        config.getServiceToggles().setGuardrailsValidationsEnabled(guardrailsEnabled);
        config.getAiLabels().setEnabled(true);
        config.getTopics().setEnabled(true);
        config.getInference().setMaxConcurrentDecides(maxConcurrentDecides);
        config.getAiLabels().setLoadConcurrency(loadConcurrency);
        config.getTopics().getVectors().setEmbedConcurrency(embedConcurrency);
        config.getInference().setMaxConcurrentGenerates(maxConcurrentGenerates);
        config.getJerseyClient().setMaxConnectionsPerRoute(maxConnectionsPerRoute);

        assertThat(VALIDATOR.validateProperty(config, "inferenceCallsWithinConnectionPool")).hasSize(valid ? 0 : 1);
    }

    static Stream<Arguments> featuresOnAndOff() {
        return Stream.of(
                arguments("both on", true, true, false),
                arguments("ai-labels off", false, true, true),
                arguments("Topics off", true, false, true),
                arguments("both off", false, false, true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("featuresOnAndOff")
    @DisplayName("loads, embeds and generates count against the pool only when their feature is on")
    void loadsAndEmbedsCountOnlyWhenTheirFeatureIsOn(String description, boolean aiLabelsEnabled,
            boolean topicsEnabled, boolean valid) {
        var config = TestConfigUtils.loadConfigTest();
        config.getServiceToggles().setGuardrailsValidationsEnabled(true);
        config.getAiLabels().setEnabled(aiLabelsEnabled);
        config.getTopics().setEnabled(topicsEnabled);
        // Decides alone leave room, with loads, embeds and generates on top they fill the pool.
        config.getInference().setMaxConcurrentDecides(55);
        config.getAiLabels().setLoadConcurrency(4);
        config.getTopics().getVectors().setEmbedConcurrency(4);
        config.getInference().setMaxConcurrentGenerates(4);
        config.getJerseyClient().setMaxConnectionsPerRoute(64);

        assertThat(VALIDATOR.validateProperty(config, "inferenceCallsWithinConnectionPool")).hasSize(valid ? 0 : 1);
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("topicsLlmDeadlines")
    @DisplayName("Topics LLM deadlines stay within inference.limits.maxGenerateBatchDeadline")
    void topicsLlmDeadlinesStayWithinTheLimit(String description, String overLimitDeadline) {
        var config = TestConfigUtils.loadConfigTest();
        var max = config.getInference().getLimits().getMaxGenerateBatchDeadline();
        var aboveMax = Duration.milliseconds(max.toMilliseconds() + 1);
        var run = config.getTopics().getRun();
        run.setLlmDeadline("llmDeadline".equals(overLimitDeadline) ? aboveMax : max);
        run.setNamingDeadline("namingDeadline".equals(overLimitDeadline) ? aboveMax : max);
        run.setVerifyDeadline("verifyDeadline".equals(overLimitDeadline) ? aboveMax : max);

        assertThat(VALIDATOR.validateProperty(config, "topicsLlmDeadlinesWithinLimits"))
                .hasSize(overLimitDeadline == null ? 0 : 1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("decideDeadlines")
    @DisplayName("the decide deadline stays within inference.limits.maxDecideRealtimeDeadline")
    void decideDeadlineStaysWithinTheLimit(String description, long overLimitMs, boolean valid) {
        var config = TestConfigUtils.loadConfigTest();
        var max = config.getInference().getLimits().getMaxDecideRealtimeDeadline();
        config.getInference().getRealtime()
                .setDecideDeadline(Duration.milliseconds(max.toMilliseconds() + overLimitMs));

        assertThat(VALIDATOR.validateProperty(config, "decideDeadlineWithinLimits")).hasSize(valid ? 0 : 1);
    }

    static Stream<Arguments> decideDeadlines() {
        return Stream.of(
                arguments("at the limit", 0L, true),
                arguments("over the limit", 1L, false));
    }

    static Stream<Arguments> topicsLlmDeadlines() {
        return Stream.of(
                arguments("every deadline at the limit", null),
                arguments("llmDeadline over the limit", "llmDeadline"),
                arguments("namingDeadline over the limit", "namingDeadline"),
                arguments("verifyDeadline over the limit", "verifyDeadline"));
    }
}
