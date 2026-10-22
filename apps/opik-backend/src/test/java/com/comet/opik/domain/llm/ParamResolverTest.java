package com.comet.opik.domain.llm;

import com.comet.opik.api.ModelParams;
import com.comet.opik.api.ModelParams.NumberParam;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.domain.llm.ParamResolver.ParamChange;
import com.comet.opik.domain.llm.ParamResolver.ParamChange.Reason;
import com.comet.opik.domain.llm.ParamResolver.Resolution;
import com.comet.opik.infrastructure.LlmModelRegistryConfig;
import com.comet.opik.infrastructure.llm.LlmModelRegistryService;
import com.comet.opik.infrastructure.llm.LlmModelRegistryTestUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.UserMessage;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
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
    private static final String MESSAGE = RandomStringUtils.secure().nextAlphanumeric(20);
    private static final int SEED = RandomUtils.secure().randomInt();

    private static final ParamChange TEMPERATURE_REFUSED = new ParamChange("temperature", Reason.REFUSED_BY_MODEL);
    private static final ParamChange TOP_P_REFUSED = new ParamChange("top_p", Reason.REFUSED_BY_MODEL);
    private static final ParamChange TEMPERATURE_THINKING = new ParamChange("temperature", Reason.THINKING_ENABLED);
    private static final ParamChange TOP_P_THINKING = new ParamChange("top_p", Reason.THINKING_ENABLED);
    private static final ParamChange TOP_P_EXCLUSIVE = new ParamChange("top_p", Reason.EXCLUSIVE_SAMPLING);

    private static ParamResolver resolverWithOpenAiParams(ModelParams params) {
        var factory = mock(LlmProviderFactory.class);
        when(factory.catalogParams(anyString())).thenReturn(Optional.of(params));
        return new ParamResolver(factory, bundledRegistry());
    }

    private static <T> Resolution<T> resolution(T request, ParamChange... changes) {
        return new Resolution<>(request, List.of(changes));
    }

    private static ChatCompletionRequest request(String model, Double temperature, Double topP) {
        return request(model, temperature, topP, null);
    }

    private static ChatCompletionRequest request(String model, Double temperature, Double topP,
            Map<String, Object> customParameters) {
        return ChatCompletionRequest.builder()
                .model(model)
                .messages(List.of(UserMessage.from(MESSAGE)))
                .temperature(temperature)
                .topP(topP)
                .seed(SEED)
                .customParameters(customParameters)
                .build();
    }

    private static ChatCompletionRequest thinkingRequest(String model, Double temperature, Double topP,
            String thinkingType) {
        return request(model, temperature, topP, Map.of("thinking", Map.of("type", thinkingType)));
    }

    private static Map<String, Object> customParameters(String json) {
        return JsonUtils.readValue(json, MAP_TYPE);
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
        assertThat(RESOLVER.resolve(request(model, 0.7, 0.9), null))
                .isEqualTo(resolution(request(model, 0.7, null), TOP_P_EXCLUSIVE));
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
        var request = request(model, 0.7, 0.9);

        assertThat(RESOLVER.resolve(request, null)).isEqualTo(resolution(request));
    }

    /**
     * A trailing separator leaves no model segment at all. Falling back to the whole id here would
     * classify it by the gateway's name, and would disagree with the frontend, which reads the same
     * id as having no model.
     */
    @Test
    void doesNotClassifyAnIdWithNoModelSegment() {
        var request = request("custom-llm/claude-gw/", 0.7, 0.9);

        assertThat(RESOLVER.resolve(request, null)).isEqualTo(resolution(request));
    }

    @Test
    void stillMatchesClaudeBehindSuchAGateway() {
        var model = "custom-llm/claude-gw/claude-opus-4-6";

        assertThat(RESOLVER.resolve(request(model, 0.7, 0.9), null))
                .isEqualTo(resolution(request(model, 0.7, null), TOP_P_EXCLUSIVE));
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
        assertThat(RESOLVER.resolve(request(model, 0.7, 0.9), null))
                .isEqualTo(resolution(request(model, null, null), TEMPERATURE_REFUSED, TOP_P_REFUSED));
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
        assertThat(RESOLVER.resolve(request(model, 0.7, 0.9), null))
                .isEqualTo(resolution(request(model, null, null), TEMPERATURE_REFUSED, TOP_P_REFUSED));
    }

    /**
     * An id we cannot place is left alone rather than stripped: a proxy may be serving a capable
     * Claude under a name of its own, and silently dropping a temperature someone set is the defect
     * this normalizer exists to prevent, pointed the other way.
     */
    @Test
    void leavesAnUnrecognisedClaudeItsTemperature() {
        var request = request("custom-llm/gw/my-claude-deployment", 0.7, null);

        assertThat(RESOLVER.resolve(request, null)).isEqualTo(resolution(request));
    }

    @Test
    void dropsALoneTemperatureForAModelThatTakesNeither() {
        var model = "custom-llm/gw/claude-sonnet-5";

        assertThat(RESOLVER.resolve(request(model, 0.7, null), null))
                .isEqualTo(resolution(request(model, null, null), TEMPERATURE_REFUSED));
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
        var request = request(model, 0.7, null);

        assertThat(RESOLVER.resolve(request, null)).isEqualTo(resolution(request));
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
        var model = "custom-llm/gw/claude-sonnet-4-6";

        assertThat(RESOLVER.resolve(thinkingRequest(model, 0.7, 0.9, type), null)).isEqualTo(
                resolution(thinkingRequest(model, null, null, type), TEMPERATURE_THINKING, TOP_P_THINKING));
    }

    @ParameterizedTest
    @ValueSource(strings = {"disabled", "DISABLED", "  "})
    void leavesTemperatureWhenThinkingIsNotEnabled(String type) {
        var request = thinkingRequest("custom-llm/gw/claude-sonnet-4-6", 0.7, null, type);

        assertThat(RESOLVER.resolve(request, null)).isEqualTo(resolution(request));
    }

    /** Asserted from the Top P side too, since the thinking gate drops both and only one is live. */
    @ParameterizedTest
    @ValueSource(strings = {"disabled", "DISABLED", "  "})
    void leavesTopPWhenThinkingIsNotEnabled(String type) {
        var request = thinkingRequest("custom-llm/gw/claude-sonnet-4-6", null, 0.9, type);

        assertThat(RESOLVER.resolve(request, null)).isEqualTo(resolution(request));
    }

    /**
     * On the models that think only when asked, an effort level makes the Anthropic mapper send adaptive thinking
     * next to it, so it costs both like a thinking block does.
     */
    @ParameterizedTest
    @ValueSource(strings = {"claude-sonnet-4-6", "claude-opus-4-6-20260205"})
    void dropsBothWhenAnEffortTurnsThinkingOn(String model) {
        var effort = Map.<String, Object>of("output_config", Map.of("effort", "low"));

        assertThat(RESOLVER.resolve(request(model, 0.7, 0.9, effort), null))
                .isEqualTo(resolution(request(model, null, null, effort), TEMPERATURE_THINKING, TOP_P_THINKING));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "claude-sonnet-4-6 | {\"output_config\": {\"effort\": \"low\"}, \"thinking\": {\"type\": \"disabled\"}}",
            "claude-sonnet-4-6 | {\"output_config\": {\"format\": {\"type\": \"json_schema\"}}}",
            "claude-opus-4-5-20251101 | {\"output_config\": {\"effort\": \"high\"}}",
            "anthropic/claude-sonnet-4.6 | {\"output_config\": {\"effort\": \"low\"}}"})
    void keepsTemperatureWhenTheEffortLeavesThinkingOff(String model, String customParameters) {
        var request = request(model, 0.7, null, customParameters(customParameters));

        assertThat(RESOLVER.resolve(request, null)).isEqualTo(resolution(request));
    }

    @Test
    void leavesASamplingCapableClaudeAlone() {
        var request = request("custom-llm/gw/claude-sonnet-4-6", 0.7, null);

        assertThat(RESOLVER.resolve(request, null)).isEqualTo(resolution(request));
    }

    @Test
    void keepsTopPForClaudeWhenTemperatureIsAbsent() {
        var request = request("claude-opus-4-6", null, 0.9);

        assertThat(RESOLVER.resolve(request, null)).isEqualTo(resolution(request));
    }

    @Test
    void leavesModelsWithoutTheConstraintAlone() {
        var request = request("gpt-4o", 0.7, 0.9);

        assertThat(RESOLVER.resolve(request, null)).isEqualTo(resolution(request));
    }

    @Test
    void returnsTheSameRequestWhenNothingNeedsDropping() {
        var original = request("claude-opus-4-6", 0.7, null);

        var resolution = RESOLVER.resolve(original, null);

        assertThat(resolution.request()).isSameAs(original);
        assertThat(resolution.changes()).isEmpty();
    }

    @Test
    void toleratesARequestWithNoModel() {
        var original = request(null, 0.7, 0.9);

        var resolution = RESOLVER.resolve(original, null);

        assertThat(resolution).isEqualTo(resolution(original));
        assertThat(resolution.request()).isSameAs(original);
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
        var opusTemperature = request("claude-opus-4-6", 0.7, null);
        assertThat(RESOLVER.resolve(opusTemperature, null)).isEqualTo(resolution(opusTemperature));

        var resolver = resolverWithOverride(tempDir, new LlmModelRegistryConfig().getCuratedResource());

        assertOverrideRefusesOpusTemperatureAndSonnetTopP(resolver);
    }

    @Test
    void followsALocalOverrideWithTheCuratedLayerTurnedOff(@TempDir Path tempDir) throws IOException {
        assertOverrideRefusesOpusTemperatureAndSonnetTopP(resolverWithOverride(tempDir, ""));
    }

    private static void assertOverrideRefusesOpusTemperatureAndSonnetTopP(ParamResolver resolver) {
        assertThat(resolver.resolve(request("claude-opus-4-6", 0.7, null), null))
                .isEqualTo(resolution(request("claude-opus-4-6", null, null), TEMPERATURE_REFUSED));
        var sonnetTemperature = request("claude-sonnet-4-6", 0.7, null);
        assertThat(resolver.resolve(sonnetTemperature, null)).isEqualTo(resolution(sonnetTemperature));
        assertThat(resolver.resolve(request("claude-sonnet-4-6", null, 0.9), null))
                .isEqualTo(resolution(request("claude-sonnet-4-6", null, null), TOP_P_REFUSED));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void keepsDroppingWhatTheShippedCatalogRefusesWhenAnOverrideAcceptsIt(boolean curatedLayerOn,
            @TempDir Path tempDir) throws IOException {
        var registry = registryWithOverride(tempDir, "llm-models-override-claude-accepts-sampling.yaml",
                curatedLayerOn ? new LlmModelRegistryConfig().getCuratedResource() : "");
        assertThat(registry.paramsOf("anthropic", "claude-sonnet-5").temperature().accepted()).isTrue();

        var resolver = new ParamResolver(mock(LlmProviderFactory.class), registry);

        assertThat(resolver.resolve(request("claude-sonnet-5", 0.7, 0.9), null)).isEqualTo(
                resolution(request("claude-sonnet-5", null, null), TEMPERATURE_REFUSED, TOP_P_REFUSED));
        assertThat(resolver.resolve(judge("claude-sonnet-5", 0.7, null)))
                .isEqualTo(resolution(judge("claude-sonnet-5", null, null), TEMPERATURE_REFUSED));
    }

    @Test
    void dropsTheSettingsTheOpenAiCatalogParamsRefuseAndKeepsTheRest() {
        var request = ChatCompletionRequest.builder()
                .model("gpt-5-mini")
                .messages(List.of(UserMessage.from(MESSAGE)))
                .temperature(0.7)
                .topP(0.9)
                .frequencyPenalty(0.5)
                .presencePenalty(0.4)
                .maxCompletionTokens(100)
                .seed(SEED)
                .customParameters(Map.of("verbosity", "low"))
                .build();

        var resolution = resolverWithOpenAiParams(OPENAI_REASONING).resolve(request, null);

        assertThat(resolution).isEqualTo(resolution(
                ChatCompletionRequest.builder()
                        .model("gpt-5-mini")
                        .messages(List.of(UserMessage.from(MESSAGE)))
                        .maxCompletionTokens(100)
                        .seed(SEED)
                        .customParameters(Map.of("verbosity", "low"))
                        .build(),
                TEMPERATURE_REFUSED,
                TOP_P_REFUSED,
                new ParamChange("frequency_penalty", Reason.REFUSED_BY_MODEL),
                new ParamChange("presence_penalty", Reason.REFUSED_BY_MODEL)));
    }

    @Test
    void returnsTheSameRequestWhenTheOpenAiCatalogParamsRefuseNothingThatIsSet() {
        var request = ChatCompletionRequest.builder().model("o3").maxCompletionTokens(100).build();

        var resolution = resolverWithOpenAiParams(OPENAI_REASONING).resolve(request, null);

        assertThat(resolution.request()).isSameAs(request);
        assertThat(resolution.changes()).isEmpty();
    }

    private static ChatCompletionRequest.Builder openAiRequest(String model) {
        return ChatCompletionRequest.builder()
                .model(model)
                .messages(List.of(UserMessage.from(MESSAGE)))
                .temperature(0.7)
                .topP(0.9)
                .maxCompletionTokens(100);
    }

    private static ChatCompletionRequest everyOpenAiSetting(String model) {
        return openAiRequest(model)
                .frequencyPenalty(0.5)
                .presencePenalty(0.4)
                .stop(List.of("END"))
                .seed(SEED)
                .build();
    }

    // gpt-4.1 is listed in the shipped registry; my-finetune is not, and still gets the OpenAI profile's route.
    @ParameterizedTest
    @ValueSource(strings = {"gpt-4.1", "my-finetune"})
    void dropsWhatTheResponsesRouteRefusesFromTheShippedCatalog(String model) {
        assertThat(RESOLVER.resolve(everyOpenAiSetting(model), ModelParams.RESPONSES_API_ROUTE)).isEqualTo(resolution(
                openAiRequest(model).stop(List.of()).build(),
                new ParamChange("frequency_penalty", Reason.REFUSED_BY_ROUTE),
                new ParamChange("presence_penalty", Reason.REFUSED_BY_ROUTE),
                new ParamChange("stop", Reason.REFUSED_BY_ROUTE),
                new ParamChange("seed", Reason.REFUSED_BY_ROUTE)));
    }

    @Test
    void keepsEveryOpenAiSettingOnChatCompletionsForAModelThatTakesThem() {
        var request = everyOpenAiSetting("gpt-4.1");
        var resolver = resolverWithOpenAiParams(bundledRegistry().paramsOf("openai", "gpt-4.1"));

        assertThat(resolver.resolve(request, null)).isEqualTo(resolution(request));
    }

    @Test
    void reportsTheModelNotTheRouteWhenTheModelItselfRefusesASetting() {
        var request = ChatCompletionRequest.builder()
                .model("o3")
                .messages(List.of(UserMessage.from(MESSAGE)))
                .temperature(0.7)
                .build();

        assertThat(resolverWithOpenAiParams(OPENAI_REASONING).resolve(request, ModelParams.RESPONSES_API_ROUTE))
                .isEqualTo(resolution(
                        ChatCompletionRequest.builder().model("o3").messages(List.of(UserMessage.from(MESSAGE)))
                                .build(),
                        TEMPERATURE_REFUSED));
    }

    @Test
    void dropsAJudgeTemperatureTheOpenAiCatalogParamsRefuse() {
        var parameters = LlmAsJudgeModelParameters.builder().name("o3").temperature(0.0).seed(SEED).build();

        assertThat(resolverWithOpenAiParams(OPENAI_REASONING).resolve(parameters)).isEqualTo(resolution(
                LlmAsJudgeModelParameters.builder().name("o3").seed(SEED).build(), TEMPERATURE_REFUSED));
    }

    private static LlmAsJudgeModelParameters judge(String model, Double temperature, String customParameters) {
        return LlmAsJudgeModelParameters.builder()
                .name(model)
                .temperature(temperature)
                .seed(SEED)
                .customParameters(customParameters == null ? null : JsonUtils.getJsonNodeFromString(customParameters))
                .build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude-sonnet-5", "anthropic/claude-opus-4.7", "custom-llm/gw/claude-fable-5-1"})
    void dropsAJudgeTemperatureAClaudeRefuses(String model) {
        assertThat(RESOLVER.resolve(judge(model, 0.7, null)))
                .isEqualTo(resolution(judge(model, null, null), TEMPERATURE_REFUSED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"claude-sonnet-4-6", "custom-llm/gw/claude-sonnet-4-6",
            "us.anthropic.claude-sonnet-4-5-20250929-v1:0"})
    void dropsAJudgeTemperatureWhenAClaudeThinks(String model) {
        var thinking = """
                {"thinking": {"type": "enabled", "budget_tokens": 1024}}""";

        assertThat(RESOLVER.resolve(judge(model, 0.7, thinking)))
                .isEqualTo(resolution(judge(model, null, thinking), TEMPERATURE_THINKING));
    }

    @Test
    void dropsAJudgeTemperatureWhenAnEffortTurnsThinkingOn() {
        var effort = """
                {"output_config": {"effort": "low"}}""";

        assertThat(RESOLVER.resolve(judge("claude-sonnet-4-6", 0.7, effort)))
                .isEqualTo(resolution(judge("claude-sonnet-4-6", null, effort), TEMPERATURE_THINKING));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "claude-sonnet-4-6 | {\"output_config\": {\"effort\": \"low\"}, \"thinking\": {\"type\": \"disabled\"}}",
            "claude-opus-4-5-20251101 | {\"output_config\": {\"effort\": \"high\"}}"})
    void keepsAJudgeTemperatureWhenTheEffortLeavesThinkingOff(String model, String customParameters) {
        var parameters = judge(model, 0.7, customParameters);

        assertThat(RESOLVER.resolve(parameters)).isEqualTo(resolution(parameters));
    }

    @Test
    void keepsAJudgeTemperatureWhenAnotherModelThinks() {
        var parameters = judge("custom-llm/gw/glm-4.6", 0.7, """
                {"thinking": {"type": "enabled"}}""");

        assertThat(RESOLVER.resolve(parameters)).isEqualTo(resolution(parameters));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"thinking\": {\"type\": \"disabled\"}}", "{\"thinking\": {\"type\": \"\"}}",
            "{\"thinking\": {\"budget_tokens\": 1024}}", "{\"thinking\": {\"type\": 1}}"})
    void keepsAJudgeTemperatureWhenThinkingIsNotOn(String customParameters) {
        var parameters = judge("claude-sonnet-4-6", 0.7, customParameters);

        assertThat(RESOLVER.resolve(parameters)).isEqualTo(resolution(parameters));
    }

    private static ParamResolver shippedOpenAiResolver(String model) {
        return resolverWithOpenAiParams(bundledRegistry().paramsOf("openai", model));
    }

    private static ChatCompletionRequest effortRequest(String model, String effort, Map<String, Object> custom) {
        return ChatCompletionRequest.builder().from(request(model, 0.5, 0.9, custom)).reasoningEffort(effort).build();
    }

    // OpenAI: "When reasoning effort is not none, remove temperature, top_p, and top_logprobs". Probed 10-22: these
    // took temperature 0.5 and top_p 0.5 at none, and gpt-5.1/5.2/5.4 also with no effort sent.
    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {"gpt-5.4,none", "gpt-6-luna,none", "gpt-5.5,none", "gpt-5.1,null",
            "gpt-5.2,null", "gpt-5.4,null"})
    void keepsTemperatureAndTopPWhenAReasoningModelRunsAtEffortNone(String model, String effort) {
        var request = effortRequest(model, effort, null);

        assertThat(shippedOpenAiResolver(model).resolve(request, null)).isEqualTo(resolution(request));
    }

    // Probed 10-22: each of these got "'temperature' does not support 0.5 with this model".
    @ParameterizedTest
    @CsvSource(nullValues = "null", value = {"gpt-5.4,low", "gpt-6-luna,null", "gpt-5.5,null", "gpt-5,none",
            "o3,null"})
    void dropsTemperatureAndTopPWhenAReasoningModelReasons(String model, String effort) {
        var request = effortRequest(model, effort, null);

        assertThat(shippedOpenAiResolver(model).resolve(request, null)).isEqualTo(resolution(
                ChatCompletionRequest.builder().from(request).temperature(null).topP(null).build(),
                TEMPERATURE_REFUSED, TOP_P_REFUSED));
    }

    @ParameterizedTest
    @CsvSource({"low,none,true", "none,low,false", "low,'  ',false", "none,'',true"})
    void readsTheEffortAnExtraBodyKeySendsInsteadOfTheTypedOne(String typed, String extraBody, boolean keeps) {
        var request = effortRequest("gpt-5.4", typed, Map.of("reasoning_effort", extraBody));

        var expected = keeps
                ? resolution(request)
                : resolution(ChatCompletionRequest.builder().from(request).temperature(null).topP(null).build(),
                        TEMPERATURE_REFUSED, TOP_P_REFUSED);
        assertThat(shippedOpenAiResolver("gpt-5.4").resolve(request, null)).isEqualTo(expected);
    }

    @Test
    void keepsTemperatureAndTopPAtEffortNoneOnTheResponsesRoute() {
        var request = effortRequest("gpt-6-luna", "none", null);

        assertThat(shippedOpenAiResolver("gpt-6-luna").resolve(request, ModelParams.RESPONSES_API_ROUTE))
                .isEqualTo(resolution(ChatCompletionRequest.builder().from(request).seed(null).build(),
                        new ParamChange("seed", Reason.REFUSED_BY_ROUTE)));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "gpt-5.4 | null",
            "gpt-6-luna | {\"reasoning_effort\": \"none\"}",
            "gpt-5.4 | {\"reasoning_effort\": \"  \"}"})
    void keepsAJudgeTemperatureWhenTheJudgeRunsAtEffortNone(String model, String customParameters) {
        var parameters = judge(model, 0.0, customParameters);

        assertThat(shippedOpenAiResolver(model).resolve(parameters)).isEqualTo(resolution(parameters));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "gpt-6-luna | null",
            "gpt-5.4 | {\"reasoning_effort\": \"low\"}",
            "gpt-6-luna | {\"reasoning_effort\": \" \"}"})
    void dropsAJudgeTemperatureWhenTheJudgeReasons(String model, String customParameters) {
        assertThat(shippedOpenAiResolver(model).resolve(judge(model, 0.0, customParameters)))
                .isEqualTo(resolution(judge(model, null, customParameters), TEMPERATURE_REFUSED));
    }

    private static final ParamChange LEVEL_MOVED = new ParamChange("thinking.level", Reason.LEVEL_NOT_OFFERED);

    private static Map<String, Object> thinking(String level) {
        return Map.of("thinking", Map.of("level", level, "include_thoughts", true));
    }

    // Probed 10-22 (online-eval check E): "minimal" got a 400 on these, on Gemini and on Vertex AI alike.
    @ParameterizedTest
    @CsvSource({"gemini-3.8-flash,minimal,low", "gemini-3.7-flash,MINIMAL,low",
            "vertex_ai/gemini-3.1-pro-preview,minimal,low", "vertex_ai/gemini-3.8-flash,minimal,low"})
    void movesAGeminiThinkingLevelTheModelDoesNotOfferToTheNearestOne(String model, String level, String nearest) {
        assertThat(RESOLVER.resolve(request(model, null, null, thinking(level)), null))
                .isEqualTo(resolution(request(model, null, null, thinking(nearest)), LEVEL_MOVED));
    }

    @ParameterizedTest
    @CsvSource({"gemini-3.6-flash,minimal", "gemini-3.8-flash,high", "gemini-3.8-flash,off",
            "gemini-3.8-flash,unknown", "gpt-4o,minimal"})
    void leavesAThinkingLevelAloneWhenTheModelOffersItOrItIsNotAGeminiLevel(String model, String level) {
        var request = request(model, null, null, thinking(level));

        assertThat(RESOLVER.resolve(request, null)).isEqualTo(resolution(request));
    }

    @Test
    void movesAJudgesGeminiThinkingLevelTheModelDoesNotOffer() {
        var saved = """
                {"thinking": {"level": "minimal", "include_thoughts": true}, "other": 1}""";

        assertThat(RESOLVER.resolve(judge("gemini-3.8-flash", null, saved))).isEqualTo(resolution(
                judge("gemini-3.8-flash", null, """
                        {"thinking": {"level": "low", "include_thoughts": true}, "other": 1}"""),
                LEVEL_MOVED));
    }

    @Test
    void leavesAJudgesGeminiThinkingLevelTheModelOffers() {
        var parameters = judge("vertex_ai/gemini-3.6-flash", null, """
                {"thinking": {"level": "minimal"}}""");

        assertThat(RESOLVER.resolve(parameters)).isEqualTo(resolution(parameters));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "null", value = {
            "0.7  | {\"reasoning_effort\": \"low\", \"max_completion_tokens\": 400} | {\"max_completion_tokens\": 400} | reasoning_effort",
            "null | {\"reasoning_effort\": \"low\"}                                 | null                           | reasoning_effort",
            "null | {\"reasoning\": {\"effort\": \"low\"}, \"seed\": 7}                 | {\"seed\": 7}                  | reasoning.effort",
            "null | {\"reasoning\": {\"effort\": \"low\", \"summary\": \"auto\"}}       | {\"reasoning\": {\"summary\": \"auto\"}} | reasoning.effort"})
    void dropsAJudgeEffortTheModelRefusesAndKeepsTheRest(Double temperature, String saved, String kept,
            String dropped) {
        assertThat(shippedOpenAiResolver("gpt-4o-mini").resolve(judge("gpt-4o-mini", temperature, saved)))
                .isEqualTo(resolution(judge("gpt-4o-mini", temperature, kept),
                        new ParamChange(dropped, Reason.REFUSED_BY_MODEL)));
    }

    @Test
    void dropsBothShapesOfAJudgeEffortTheModelRefuses() {
        var parameters = judge("gpt-4o-mini", null, """
                {"reasoning_effort": "low", "reasoning": {"effort": "low"}}""");

        assertThat(shippedOpenAiResolver("gpt-4o-mini").resolve(parameters))
                .isEqualTo(resolution(judge("gpt-4o-mini", null, null),
                        new ParamChange("reasoning_effort", Reason.REFUSED_BY_MODEL),
                        new ParamChange("reasoning.effort", Reason.REFUSED_BY_MODEL)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"gpt-5.4-mini", "o3", "gpt-6-luna"})
    void keepsAJudgeEffortTheModelTakes(String model) {
        var parameters = judge(model, null, """
                {"reasoning_effort": "low"}""");

        assertThat(shippedOpenAiResolver(model).resolve(parameters)).isEqualTo(resolution(parameters));
    }

    @ParameterizedTest
    @ValueSource(strings = {"openai/gpt-4o-mini", "custom-llm/gw/gpt-4o-mini", "claude-haiku-4-5-20251001"})
    void keepsAJudgeEffortOnModelsTheOpenAiCatalogDoesNotPlace(String model) {
        var parameters = judge(model, null, """
                {"reasoning_effort": "low"}""");

        assertThat(RESOLVER.resolve(parameters)).isEqualTo(resolution(parameters));
    }
}
