package com.comet.opik.domain.llm;

import com.comet.opik.api.ModelParams;
import com.comet.opik.api.ModelParams.NumberParam;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.domain.llm.ParamResolver.ParamChange;
import com.comet.opik.domain.llm.ParamResolver.ParamChange.Reason;
import com.comet.opik.infrastructure.LlmModelRegistryConfig;
import com.comet.opik.infrastructure.llm.LlmModelRegistryService;
import com.comet.opik.infrastructure.llm.LlmModelRegistryTestUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.comet.opik.infrastructure.llm.LlmModelRegistryTestUtils.bundledRegistry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ParamResolverTest {

    private static final NumberParam REFUSED = new NumberParam(false, null, null, null);
    private static final ModelParams OPENAI_REASONING = ModelParams.builder()
            .temperature(REFUSED).topP(REFUSED).frequencyPenalty(REFUSED).presencePenalty(REFUSED).build();

    private static final ParamResolver RESOLVER = new ParamResolver(mock(LlmProviderFactory.class), bundledRegistry());
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static ParamResolver resolverWithOpenAiParams(ModelParams params) {
        var factory = mock(LlmProviderFactory.class);
        when(factory.openAiParams(anyString())).thenReturn(Optional.of(params));
        return new ParamResolver(factory, bundledRegistry());
    }

    private static ChatCompletionRequest resolve(ChatCompletionRequest request) {
        return RESOLVER.resolve(request, null).request();
    }

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
        var normalized = resolve(request(model, 0.7, 0.9));

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
        var normalized = resolve(request(model, 0.7, 0.9));

        assertThat(normalized.topP()).isEqualTo(0.9);
    }

    /**
     * A trailing separator leaves no model segment at all. Falling back to the whole id here would
     * classify it by the gateway's name, and would disagree with the frontend, which reads the same
     * id as having no model.
     */
    @Test
    void doesNotClassifyAnIdWithNoModelSegment() {
        var normalized = resolve(request("custom-llm/claude-gw/", 0.7, 0.9));

        assertThat(normalized.topP()).isEqualTo(0.9);
    }

    @Test
    void stillMatchesClaudeBehindSuchAGateway() {
        var normalized = resolve(request("custom-llm/claude-gw/claude-opus-4-6", 0.7, 0.9));

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
        var normalized = resolve(request(model, 0.7, 0.9));

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
        var normalized = resolve(request(model, 0.7, 0.9));

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
        var normalized = resolve(request("custom-llm/gw/my-claude-deployment", 0.7, null));

        assertThat(normalized.temperature()).isEqualTo(0.7);
    }

    @Test
    void dropsALoneTemperatureForAModelThatTakesNeither() {
        var normalized = resolve(request("custom-llm/gw/claude-sonnet-5", 0.7, null));

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
        var normalized = resolve(request(model, 0.7, null));

        assertThat(normalized.temperature()).isEqualTo(0.7);
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
        var normalized = resolve(
                thinkingRequest("custom-llm/gw/claude-sonnet-4-6", 0.7, 0.9, type));

        assertThat(normalized.temperature()).isNull();
        assertThat(normalized.topP()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "DISABLED", "  "})
    void leavesTemperatureWhenThinkingIsNotEnabled(String type) {
        var normalized = resolve(
                thinkingRequest("custom-llm/gw/claude-sonnet-4-6", 0.7, null, type));

        assertThat(normalized.temperature()).isEqualTo(0.7);
        assertThat(normalized.topP()).isNull();
    }

    /** Asserted from the Top P side too, since the thinking gate drops both and only one is live. */
    @ParameterizedTest
    @ValueSource(strings = {"disabled", "DISABLED", "  "})
    void leavesTopPWhenThinkingIsNotEnabled(String type) {
        var normalized = resolve(
                thinkingRequest("custom-llm/gw/claude-sonnet-4-6", null, 0.9, type));

        assertThat(normalized.topP()).isEqualTo(0.9);
        assertThat(normalized.temperature()).isNull();
    }

    /**
     * On the models that think only when asked, an effort level makes the Anthropic mapper send adaptive thinking
     * next to it, so it costs both like a thinking block does.
     */
    @ParameterizedTest
    @ValueSource(strings = {"claude-sonnet-4-6", "claude-opus-4-6-20260205"})
    void dropsBothWhenAnEffortTurnsThinkingOn(String model) {
        var resolution = RESOLVER.resolve(ChatCompletionRequest.builder()
                .model(model)
                .temperature(0.7)
                .topP(0.9)
                .customParameters(Map.of("output_config", Map.of("effort", "low")))
                .build(), null);

        assertThat(resolution.request().temperature()).isNull();
        assertThat(resolution.request().topP()).isNull();
        assertThat(resolution.changes()).containsExactly(
                new ParamChange("temperature", Reason.THINKING_ENABLED),
                new ParamChange("top_p", Reason.THINKING_ENABLED));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "claude-sonnet-4-6 | {\"output_config\": {\"effort\": \"low\"}, \"thinking\": {\"type\": \"disabled\"}}",
            "claude-sonnet-4-6 | {\"output_config\": {\"format\": {\"type\": \"json_schema\"}}}",
            "claude-opus-4-5-20251101 | {\"output_config\": {\"effort\": \"high\"}}",
            "anthropic/claude-sonnet-4.6 | {\"output_config\": {\"effort\": \"low\"}}"})
    void keepsTemperatureWhenTheEffortLeavesThinkingOff(String model, String customParameters) {
        var request = ChatCompletionRequest.builder()
                .model(model)
                .temperature(0.7)
                .customParameters(JsonUtils.readValue(customParameters, MAP_TYPE))
                .build();

        assertThat(RESOLVER.resolve(request, null).request()).isSameAs(request);
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
        var normalized = resolve(request("custom-llm/gw/claude-sonnet-4-6", 0.7, null));

        assertThat(normalized.temperature()).isEqualTo(0.7);
    }

    @Test
    void keepsTopPForClaudeWhenTemperatureIsAbsent() {
        var normalized = resolve(request("claude-opus-4-6", null, 0.9));

        assertThat(normalized.topP()).isEqualTo(0.9);
    }

    @Test
    void leavesModelsWithoutTheConstraintAlone() {
        var normalized = resolve(request("gpt-4o", 0.7, 0.9));

        assertThat(normalized.temperature()).isEqualTo(0.7);
        assertThat(normalized.topP()).isEqualTo(0.9);
    }

    @Test
    void returnsTheSameRequestWhenNothingNeedsDropping() {
        var original = request("claude-opus-4-6", 0.7, null);

        assertThat(resolve(original)).isSameAs(original);
    }

    @Test
    void toleratesARequestWithNoModel() {
        var original = request(null, 0.7, 0.9);

        assertThat(resolve(original)).isSameAs(original);
    }

    private LlmModelRegistryService registryWithOverride(Path tempDir, String overrideResource,
            String curatedResource) throws IOException {
        var overridePath = tempDir.resolve("override.yaml");
        try (var override = getClass().getClassLoader().getResourceAsStream(overrideResource)) {
            Files.copy(override, overridePath);
        }
        var registryConfig = new LlmModelRegistryConfig();
        registryConfig.setLocalOverridePath(overridePath.toString());
        registryConfig.setCuratedResource(curatedResource);
        return LlmModelRegistryTestUtils.registry(registryConfig);
    }

    private ParamResolver resolverWithOverride(Path tempDir, String curatedResource) throws IOException {
        return new ParamResolver(mock(LlmProviderFactory.class),
                registryWithOverride(tempDir, "llm-models-override-claude-no-sampling.yaml", curatedResource));
    }

    @Test
    void followsALocalOverrideOfTheShippedCatalog(@TempDir Path tempDir) throws IOException {
        var request = request("claude-opus-4-6", 0.7, null);
        assertThat(resolve(request).temperature()).isEqualTo(0.7);

        var resolver = resolverWithOverride(tempDir, new LlmModelRegistryConfig().getCuratedResource());

        var resolution = resolver.resolve(request, null);

        assertThat(resolution.request().temperature()).isNull();
        assertThat(resolution.changes()).containsExactly(new ParamChange("temperature", Reason.REFUSED_BY_MODEL));
        assertThat(resolver.resolve(request("claude-sonnet-4-6", 0.7, null), null).request().temperature())
                .isEqualTo(0.7);
        assertThat(resolver.resolve(request("claude-sonnet-4-6", null, 0.9), null).changes())
                .containsExactly(new ParamChange("top_p", Reason.REFUSED_BY_MODEL));
    }

    @Test
    void followsALocalOverrideWithTheCuratedLayerTurnedOff(@TempDir Path tempDir) throws IOException {
        var resolver = resolverWithOverride(tempDir, "");

        assertThat(resolver.resolve(request("claude-opus-4-6", 0.7, null), null).changes())
                .containsExactly(new ParamChange("temperature", Reason.REFUSED_BY_MODEL));
        assertThat(resolver.resolve(request("claude-sonnet-4-6", 0.7, null), null).request().temperature())
                .isEqualTo(0.7);
        assertThat(resolver.resolve(request("claude-sonnet-4-6", null, 0.9), null).changes())
                .containsExactly(new ParamChange("top_p", Reason.REFUSED_BY_MODEL));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void keepsDroppingWhatTheShippedCatalogRefusesWhenAnOverrideAcceptsIt(boolean curatedLayerOn,
            @TempDir Path tempDir) throws IOException {
        var registry = registryWithOverride(tempDir, "llm-models-override-claude-accepts-sampling.yaml",
                curatedLayerOn ? new LlmModelRegistryConfig().getCuratedResource() : "");
        assertThat(registry.paramsOf("anthropic", "claude-sonnet-5").temperature().accepted()).isTrue();

        var resolver = new ParamResolver(mock(LlmProviderFactory.class), registry);

        assertThat(resolver.resolve(request("claude-sonnet-5", 0.7, 0.9), null).changes()).containsExactly(
                new ParamChange("temperature", Reason.REFUSED_BY_MODEL),
                new ParamChange("top_p", Reason.REFUSED_BY_MODEL));
        assertThat(resolver.resolve(judge("claude-sonnet-5", null)).request().temperature()).isNull();
    }

    @Test
    void reportsEachSettingItDropsAndWhy() {
        var resolution = RESOLVER.resolve(request("claude-sonnet-5", 0.7, 0.9), null);

        assertThat(resolution.changes()).containsExactly(
                new ParamChange("temperature", Reason.REFUSED_BY_MODEL),
                new ParamChange("top_p", Reason.REFUSED_BY_MODEL));
    }

    @Test
    void reportsTheTopPItDropsForAModelThatTakesOnlyOneOfThem() {
        var resolution = RESOLVER.resolve(request("claude-sonnet-4-6", 0.7, 0.9), null);

        assertThat(resolution.changes())
                .containsExactly(new ParamChange("top_p", Reason.EXCLUSIVE_SAMPLING));
    }

    @Test
    void dropsTheSettingsTheOpenAiCatalogParamsRefuseAndKeepsTheRest() {
        var request = ChatCompletionRequest.builder()
                .model("gpt-5-mini")
                .temperature(0.7)
                .topP(0.9)
                .frequencyPenalty(0.5)
                .presencePenalty(0.4)
                .maxCompletionTokens(100)
                .seed(7)
                .build();

        var resolution = resolverWithOpenAiParams(OPENAI_REASONING).resolve(request, null);

        var normalized = resolution.request();
        assertThat(normalized.temperature()).isNull();
        assertThat(normalized.topP()).isNull();
        assertThat(normalized.frequencyPenalty()).isNull();
        assertThat(normalized.presencePenalty()).isNull();
        assertThat(normalized.model()).isEqualTo("gpt-5-mini");
        assertThat(normalized.maxCompletionTokens()).isEqualTo(100);
        assertThat(normalized.seed()).isEqualTo(7);
        assertThat(resolution.changes()).containsExactly(
                new ParamChange("temperature", Reason.REFUSED_BY_MODEL),
                new ParamChange("top_p", Reason.REFUSED_BY_MODEL),
                new ParamChange("frequency_penalty", Reason.REFUSED_BY_MODEL),
                new ParamChange("presence_penalty", Reason.REFUSED_BY_MODEL));
    }

    @Test
    void returnsTheSameRequestWhenTheOpenAiCatalogParamsRefuseNothingThatIsSet() {
        var request = ChatCompletionRequest.builder().model("o3").maxCompletionTokens(100).build();

        var resolution = resolverWithOpenAiParams(OPENAI_REASONING).resolve(request, null);

        assertThat(resolution.request()).isSameAs(request);
        assertThat(resolution.changes()).isEmpty();
    }

    private static ChatCompletionRequest everyOpenAiSetting(String model) {
        return ChatCompletionRequest.builder()
                .model(model)
                .temperature(0.7)
                .topP(0.9)
                .frequencyPenalty(0.5)
                .presencePenalty(0.4)
                .stop(List.of("END"))
                .seed(7)
                .maxCompletionTokens(100)
                .build();
    }

    // gpt-4.1 is listed in the shipped registry; my-finetune is not, and still gets the OpenAI profile's route.
    @ParameterizedTest
    @ValueSource(strings = {"gpt-4.1", "my-finetune"})
    void dropsWhatTheResponsesRouteRefusesFromTheShippedCatalog(String model) {
        var resolution = RESOLVER.resolve(everyOpenAiSetting(model), ParamResolver.RESPONSES_API_ROUTE);

        var expected = ChatCompletionRequest.builder()
                .from(everyOpenAiSetting(model))
                .frequencyPenalty(null)
                .presencePenalty(null)
                .stop(List.of())
                .seed(null)
                .build();
        assertThat(resolution).isEqualTo(new ParamResolver.Resolution<>(expected, List.of(
                new ParamChange("frequency_penalty", Reason.REFUSED_BY_ROUTE),
                new ParamChange("presence_penalty", Reason.REFUSED_BY_ROUTE),
                new ParamChange("stop", Reason.REFUSED_BY_ROUTE),
                new ParamChange("seed", Reason.REFUSED_BY_ROUTE))));
    }

    @Test
    void keepsEveryOpenAiSettingOnChatCompletionsForAModelThatTakesThem() {
        var request = everyOpenAiSetting("gpt-4.1");
        var resolver = resolverWithOpenAiParams(bundledRegistry().paramsOf("openai", "gpt-4.1"));

        var resolution = resolver.resolve(request, null);

        assertThat(resolution).isEqualTo(new ParamResolver.Resolution<>(request, List.of()));
    }

    @Test
    void reportsTheModelNotTheRouteWhenTheModelItselfRefusesASetting() {
        var request = ChatCompletionRequest.builder().model("o3").temperature(0.7).build();

        var resolution = resolverWithOpenAiParams(OPENAI_REASONING).resolve(request,
                ParamResolver.RESPONSES_API_ROUTE);

        assertThat(resolution).isEqualTo(new ParamResolver.Resolution<>(
                ChatCompletionRequest.builder().model("o3").build(),
                List.of(new ParamChange("temperature", Reason.REFUSED_BY_MODEL))));
    }

    @Test
    void dropsAJudgeTemperatureTheOpenAiCatalogParamsRefuse() {
        var parameters = LlmAsJudgeModelParameters.builder().name("o3").temperature(0.0).seed(3).build();

        var resolution = resolverWithOpenAiParams(OPENAI_REASONING).resolve(parameters);

        assertThat(resolution.request().temperature()).isNull();
        assertThat(resolution.request().seed()).isEqualTo(3);
        assertThat(resolution.changes()).containsExactly(new ParamChange("temperature", Reason.REFUSED_BY_MODEL));
    }

    private static LlmAsJudgeModelParameters judge(String model, String customParameters) {
        return LlmAsJudgeModelParameters.builder()
                .name(model)
                .temperature(0.7)
                .customParameters(customParameters == null ? null : JsonUtils.getJsonNodeFromString(customParameters))
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude-sonnet-5", "anthropic/claude-opus-4.7", "custom-llm/gw/claude-fable-5-1"})
    void dropsAJudgeTemperatureAClaudeRefuses(String model) {
        var resolution = RESOLVER.resolve(judge(model, null));

        assertThat(resolution.request().temperature()).isNull();
        assertThat(resolution.changes()).containsExactly(new ParamChange("temperature", Reason.REFUSED_BY_MODEL));
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude-sonnet-4-6", "custom-llm/gw/claude-sonnet-4-6",
            "us.anthropic.claude-sonnet-4-5-20250929-v1:0"})
    void dropsAJudgeTemperatureWhenAClaudeThinks(String model) {
        var resolution = RESOLVER.resolve(judge(model, """
                {"thinking": {"type": "enabled", "budget_tokens": 1024}}"""));

        assertThat(resolution.request().temperature()).isNull();
        assertThat(resolution.changes()).containsExactly(new ParamChange("temperature", Reason.THINKING_ENABLED));
    }

    @Test
    void dropsAJudgeTemperatureWhenAnEffortTurnsThinkingOn() {
        var resolution = RESOLVER.resolve(judge("claude-sonnet-4-6", """
                {"output_config": {"effort": "low"}}"""));

        assertThat(resolution.request().temperature()).isNull();
        assertThat(resolution.changes()).containsExactly(new ParamChange("temperature", Reason.THINKING_ENABLED));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "claude-sonnet-4-6 | {\"output_config\": {\"effort\": \"low\"}, \"thinking\": {\"type\": \"disabled\"}}",
            "claude-opus-4-5-20251101 | {\"output_config\": {\"effort\": \"high\"}}"})
    void keepsAJudgeTemperatureWhenTheEffortLeavesThinkingOff(String model, String customParameters) {
        var parameters = judge(model, customParameters);

        assertThat(RESOLVER.resolve(parameters).request()).isSameAs(parameters);
    }

    @Test
    void keepsAJudgeTemperatureWhenAnotherModelThinks() {
        var parameters = judge("custom-llm/gw/glm-4.6", """
                {"thinking": {"type": "enabled"}}""");

        assertThat(RESOLVER.resolve(parameters).request()).isSameAs(parameters);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"thinking\": {\"type\": \"disabled\"}}", "{\"thinking\": {\"type\": \"\"}}",
            "{\"thinking\": {\"budget_tokens\": 1024}}", "{\"thinking\": {\"type\": 1}}"})
    void keepsAJudgeTemperatureWhenThinkingIsNotOn(String customParameters) {
        var parameters = judge("claude-sonnet-4-6", customParameters);

        var resolution = RESOLVER.resolve(parameters);

        assertThat(resolution.request()).isSameAs(parameters);
        assertThat(resolution.changes()).isEmpty();
    }
}
