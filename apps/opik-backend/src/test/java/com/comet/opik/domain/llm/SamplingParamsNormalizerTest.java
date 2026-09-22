package com.comet.opik.domain.llm;

import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SamplingParamsNormalizerTest {

    private static ChatCompletionRequest request(String model, Double temperature, Double topP) {
        return ChatCompletionRequest.builder()
                .model(model)
                .temperature(temperature)
                .topP(topP)
                .build();
    }

    /**
     * The routing name differs per provider, and the rule is the model's, not the provider's:
     * Anthropic's own ids, Bedrock's decorated ids and OpenAI-compatible proxy names all reach
     * the same Claude model, which rejects both parameters together (OPIK-8386).
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "claude-opus-4-6",
            "claude-sonnet-4-6",
            // Sonnet 5 belongs to the takes-neither set below; this list is sampling-capable Claude.
            "anthropic/claude-sonnet-4-6",
            "us.anthropic.claude-sonnet-4-5-20250929-v1:0",
            "bedrock/anthropic.claude-3-5-sonnet-20241022-v2:0",
            "CLAUDE-HAIKU-4-5-20251001"
    })
    void dropsTopPForClaudeWhateverServesIt(String model) {
        var normalized = SamplingParamsNormalizer.normalizeRequest(request(model, 0.7, 0.9));

        assertThat(normalized.temperature()).isEqualTo(0.7);
        assertThat(normalized.topP()).isNull();
    }

    /**
     * A custom id carries the gateway in its prefix, so the model itself is what decides — otherwise
     * a provider someone named "claude-gw" strips top_p from every model behind it.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "custom-llm/claude-gw/mistral-large-2411",
            "custom-llm/anthropic-proxy/llama-3.3-70b",
            "claude-router/gpt-4o"
    })
    void doesNotTreatAGatewayNamedAfterClaudeAsClaude(String model) {
        var normalized = SamplingParamsNormalizer.normalizeRequest(request(model, 0.7, 0.9));

        assertThat(normalized.topP()).isEqualTo(0.9);
    }

    /**
     * A trailing separator leaves no model segment at all. Falling back to the whole id here would
     * classify it by the gateway's name, and would disagree with the frontend, which reads the same
     * id as having no model.
     */
    @Test
    void doesNotClassifyAnIdWithNoModelSegment() {
        var normalized = SamplingParamsNormalizer
                .normalizeRequest(request("custom-llm/claude-gw/", 0.7, 0.9));

        assertThat(normalized.topP()).isEqualTo(0.9);
    }

    @Test
    void stillMatchesClaudeBehindSuchAGateway() {
        var normalized = SamplingParamsNormalizer
                .normalizeRequest(request("custom-llm/claude-gw/claude-opus-4-6", 0.7, 0.9));

        assertThat(normalized.topP()).isNull();
    }

    /**
     * The adaptive-thinking models reject temperature and top_p outright, not merely together. The
     * Anthropic provider's mapper already gates them; these arrive by other routes and must not be
     * left with a parameter the model refuses.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "claude-sonnet-5",
            "custom-llm/gw/claude-sonnet-5",
            "anthropic/claude-sonnet-5",
            "us.anthropic.claude-sonnet-5-20250101-v1:0",
            "custom-llm/gw/claude-opus-4-7",
            "custom-llm/gw/claude-opus-4-8",
            // OpenRouter spells the version with a dot.
            "anthropic/claude-opus-4.7",
            "anthropic/claude-opus-4.8"
    })
    void dropsBothForModelsThatTakeNeither(String model) {
        var normalized = SamplingParamsNormalizer.normalizeRequest(request(model, 0.7, 0.9));

        assertThat(normalized.temperature()).isNull();
        assertThat(normalized.topP()).isNull();
    }

    /**
     * The capability list names the models that DO take sampling params, so a Claude we recognise but
     * have not marked capable — a newly synced id nobody has classified yet — is assumed not to.
     * Fable 5.1 is exactly that case today: newer than Fable 5, which takes none.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "claude-fable-5-1",
            "custom-llm/gw/claude-fable-5-1",
            "claude-opus-5",
            "custom-llm/gw/claude-opus-5",
            "anthropic/claude-fable-5.1",
            "anthropic/claude-fable-5.1:batch"
    })
    void dropsBothForARecognisedClaudeNotMarkedCapable(String model) {
        var normalized = SamplingParamsNormalizer.normalizeRequest(request(model, 0.7, 0.9));

        assertThat(normalized.temperature()).isNull();
        assertThat(normalized.topP()).isNull();
    }

    /**
     * An id we cannot place is left alone rather than stripped: a proxy may be serving a capable
     * Claude under a name of its own, and silently dropping a temperature someone set is the defect
     * this normalizer exists to prevent, pointed the other way.
     */
    @Test
    void leavesAnUnrecognisedClaudeItsTemperature() {
        var normalized = SamplingParamsNormalizer
                .normalizeRequest(request("custom-llm/gw/my-claude-deployment", 0.7, null));

        assertThat(normalized.temperature()).isEqualTo(0.7);
    }

    @Test
    void dropsALoneTemperatureForAModelThatTakesNeither() {
        var normalized = SamplingParamsNormalizer
                .normalizeRequest(request("custom-llm/gw/claude-sonnet-5", 0.7, null));

        assertThat(normalized.temperature()).isNull();
    }

    /**
     * The capable models reach us decorated: OpenRouter dots the version, sometimes drops the release
     * date and sometimes appends a variant; Bedrock adds a region and an inference profile. All of
     * them must still keep the temperature the user set.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "anthropic/claude-opus-4.6",
            "anthropic/claude-opus-4.6-fast",
            "anthropic/claude-opus-4.5",
            "anthropic/claude-haiku-4.5",
            "us.anthropic.claude-sonnet-4-5-20250929-v1:0"
    })
    void keepsTemperatureForACapableClaudeHoweverItIsSpelled(String model) {
        var normalized = SamplingParamsNormalizer.normalizeRequest(request(model, 0.7, null));

        assertThat(normalized.temperature()).isEqualTo(0.7);
    }

    /**
     * The model sync adds a dated build as a constant of its own, and the bare name then resolves to
     * it because the longest match wins — so a model classified under one of its ids has to stay
     * classified under the other. #8458 is what happens otherwise: adding
     * {@code claude-opus-4-6-20260205} silently made the capable {@code claude-opus-4-6} take no
     * sampling params, and the suite went red on a generated PR nobody had reviewed yet.
     *
     * <p>Sonnet 4.5 is the pair that exists in the enum both ways round, so it stands in for the
     * dated build a future sync will add to some other capable model.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "claude-sonnet-4-5",
            "claude-sonnet-4-5-20250929",
            "custom-llm/gw/claude-sonnet-4-5",
            "anthropic/claude-sonnet-4.5-20250929"
    })
    void classifiesADatedBuildAndItsBareNameAlike(String model) {
        var normalized = SamplingParamsNormalizer.normalizeRequest(request(model, 0.7, 0.9));

        assertThat(normalized.temperature()).isEqualTo(0.7);
        assertThat(normalized.topP()).isNull();
    }

    /**
     * Inheriting across a release date must not reach across a version: {@code claude-opus-4-7} is
     * its own model and takes neither, however close its id sits to the capable
     * {@code claude-opus-4-6}. Guards the widening in {@code ModelCapabilities.isSamplingCapable}.
     */
    @ParameterizedTest
    @ValueSource(strings = {"claude-opus-4-7", "claude-opus-4-8", "claude-fable-5-1"})
    void doesNotInheritCapabilityAcrossAVersion(String model) {
        var normalized = SamplingParamsNormalizer.normalizeRequest(request(model, 0.7, 0.9));

        assertThat(normalized.temperature()).isNull();
        assertThat(normalized.topP()).isNull();
    }

    /**
     * Extended thinking refuses the sampling params on any Claude, capable or not — the API answers
     * "temperature may only be set to 1 when thinking is enabled" and "top_p must be greater than or
     * equal to 0.95 or unset when thinking is enabled". The Anthropic provider's mapper gated this;
     * the routes that reach a Claude without it did not.
     */
    @ParameterizedTest
    @ValueSource(strings = {"enabled", "adaptive", "ENABLED"})
    void dropsBothWhenExtendedThinkingIsEnabled(String type) {
        var normalized = SamplingParamsNormalizer.normalizeRequest(
                thinkingRequest("custom-llm/gw/claude-sonnet-4-6", 0.7, 0.9, type));

        assertThat(normalized.temperature()).isNull();
        assertThat(normalized.topP()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "DISABLED", "  "})
    void leavesTemperatureWhenThinkingIsNotEnabled(String type) {
        var normalized = SamplingParamsNormalizer.normalizeRequest(
                thinkingRequest("custom-llm/gw/claude-sonnet-4-6", 0.7, null, type));

        assertThat(normalized.temperature()).isEqualTo(0.7);
        assertThat(normalized.topP()).isNull();
    }

    /** Asserted from the Top P side too, since the thinking gate drops both and only one is live. */
    @ParameterizedTest
    @ValueSource(strings = {"disabled", "DISABLED", "  "})
    void leavesTopPWhenThinkingIsNotEnabled(String type) {
        var normalized = SamplingParamsNormalizer.normalizeRequest(
                thinkingRequest("custom-llm/gw/claude-sonnet-4-6", null, 0.9, type));

        assertThat(normalized.topP()).isEqualTo(0.9);
        assertThat(normalized.temperature()).isNull();
    }

    private static ChatCompletionRequest thinkingRequest(String model, Double temperature, Double topP,
            String thinkingType) {
        return ChatCompletionRequest.builder()
                .model(model)
                .temperature(temperature)
                .topP(topP)
                .customParameters(Map.of("thinking", Map.of("type", thinkingType)))
                .build();
    }

    @Test
    void leavesASamplingCapableClaudeAlone() {
        var normalized = SamplingParamsNormalizer
                .normalizeRequest(request("custom-llm/gw/claude-sonnet-4-6", 0.7, null));

        assertThat(normalized.temperature()).isEqualTo(0.7);
    }

    @Test
    void keepsTopPForClaudeWhenTemperatureIsAbsent() {
        var normalized = SamplingParamsNormalizer.normalizeRequest(request("claude-opus-4-6", null, 0.9));

        assertThat(normalized.topP()).isEqualTo(0.9);
    }

    @Test
    void leavesModelsWithoutTheConstraintAlone() {
        var normalized = SamplingParamsNormalizer.normalizeRequest(request("gpt-4o", 0.7, 0.9));

        assertThat(normalized.temperature()).isEqualTo(0.7);
        assertThat(normalized.topP()).isEqualTo(0.9);
    }

    @Test
    void returnsTheSameRequestWhenNothingNeedsDropping() {
        var original = request("claude-opus-4-6", 0.7, null);

        assertThat(SamplingParamsNormalizer.normalizeRequest(original)).isSameAs(original);
    }

    @Test
    void toleratesARequestWithNoModel() {
        var original = request(null, 0.7, 0.9);

        assertThat(SamplingParamsNormalizer.normalizeRequest(original)).isSameAs(original);
    }
}
