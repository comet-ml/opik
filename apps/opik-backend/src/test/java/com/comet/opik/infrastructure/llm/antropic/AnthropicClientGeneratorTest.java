package com.comet.opik.infrastructure.llm.antropic;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchema;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchemaType;
import com.comet.opik.domain.llm.ModelCapabilities;
import com.comet.opik.domain.llm.structuredoutput.ToolCallingStrategy;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicChatRequestParameters;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.ws.rs.BadRequestException;
import org.apache.commons.lang3.StringUtils;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnthropicClientGeneratorTest {

    private static final int DEFAULT_MAX_TOKENS = LlmProviderAnthropicMapper.DEFAULT_MAX_COMPLETION_TOKENS;

    private final AnthropicClientGenerator generator = new AnthropicClientGenerator(new LlmProviderClientConfig());

    private AnthropicChatRequestParameters generateChatParameters(LlmAsJudgeModelParameters modelParameters) {
        var config = LlmProviderClientApiConfig.builder().apiKey("test-key").build();
        var model = (AnthropicChatModel) generator.generateChat(config, modelParameters);
        return (AnthropicChatRequestParameters) model.defaultRequestParameters();
    }

    @Nested
    @DisplayName("Sampling params capability")
    class SamplingParamsCapability {

        @ParameterizedTest
        @ValueSource(strings = {"claude-sonnet-5", "claude-opus-4-7", "claude-opus-4-8", "claude-opus-5",
                "claude-fable-5", "claude-fable-5-1"})
        void rejectsSamplingParamsForModelsNotMarkedCapable(String modelName) {
            assertThat(ModelCapabilities.rejectsSamplingParams(modelName)).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"claude-3-7-sonnet-20250219", "claude-haiku-4-5-20251001", "claude-sonnet-4-5",
                "claude-opus-4-6", "claude-opus-4-6-20260205"})
        void acceptsSamplingParamsForTheModelsNamedCapable(String modelName) {
            assertThat(ModelCapabilities.rejectsSamplingParams(modelName)).isFalse();
        }

        /**
         * A name that is not an Anthropic id tells us nothing about the model behind it, so nothing is
         * stripped: the proxy may be serving a capable Claude under a name of its own.
         */
        @ParameterizedTest
        @ValueSource(strings = {"some-unknown-model", "custom-llm/gw/my-claude-deployment",
                "custom-llm/claude-gw/mistral-large",
                // Anthropic names models claude-<family>-<version>. These fit no family it ships, so
                // they are someone's deployment name and say nothing about which Claude is behind it.
                "custom-llm/gw/claude-prod", "custom-llm/gw/claude-internal-v3", "claude-future-99",
                "claude-30-future"})
        void staysPermissiveForNamesThatAreNotAnthropicIds(String modelName) {
            assertThat(ModelCapabilities.rejectsSamplingParams(modelName)).isFalse();
        }

        /**
         * A floating alias has to be read as the model it resolves to. Haiku's newest member takes
         * sampling params and the other three families' do not, so the aliases must not share one
         * answer — and claude-haiku-latest must agree with claude-haiku-4-5 under its own id.
         */
        @ParameterizedTest
        @CsvSource({
                "~anthropic/claude-haiku-latest, false",
                "~anthropic/claude-opus-latest, true",
                "~anthropic/claude-sonnet-latest, true",
                "~anthropic/claude-fable-latest, true"})
        void readsAFloatingAliasAsTheFamilysNewestMember(String modelName, boolean rejects) {
            assertThat(ModelCapabilities.rejectsSamplingParams(modelName)).isEqualTo(rejects);
        }

        /**
         * An Anthropic id we cannot place is assumed to take none. It is far more often a model newer
         * than the capability list than an older one missing from it, and the floating aliases settle
         * it: claude-opus-latest follows the newest model by definition.
         */
        @ParameterizedTest
        @ValueSource(strings = {"us.anthropic.claude-opus-9-v1:0", "claude-opus-99",
                // A numeric segment is the next version, not a variant: an unlisted point release
                // must not inherit claude-sonnet-4-6's capability.
                "claude-sonnet-4-6-1"})
        void assumesAnUnplaceableAnthropicIdTakesNone(String modelName) {
            assertThat(ModelCapabilities.rejectsSamplingParams(modelName)).isTrue();
        }

        /** Claude 3 predates the constraint, and is recognised by shape rather than being listed. */
        @ParameterizedTest
        @ValueSource(strings = {"claude-3-haiku", "anthropic/claude-3.5-sonnet", "anthropic/claude-3.5-haiku",
                "us.anthropic.claude-3-5-sonnet-20240620-v1:0",
                // Claude 2 and Instant predate it too, and must survive the inference-profile strip
                // rather than collapsing to the bare family word.
                "anthropic.claude-v2:1", "anthropic.claude-instant-v1", "claude-2-1"})
        void staysPermissiveForTheGenerationThatPredatesTheConstraint(String modelName) {
            assertThat(ModelCapabilities.rejectsSamplingParams(modelName)).isFalse();
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   "})
        void staysPermissiveForNullOrBlankModel(String modelName) {
            assertThat(ModelCapabilities.rejectsSamplingParams(modelName)).isFalse();
        }

        /**
         * Regression guard for #7526 / #7531 → #7582. The capability opt-out must NOT be encoded as a
         * per-constant argument in the enum constant list, because that list is regenerated by the
         * "sync provider model definitions" chore, which emits single-arg {@code NAME("value")}
         * constants and silently drops any extra argument (that is how #7582 reopened this bug).
         * Driving this from {@link EnumSource} means renaming or removing one of these constants
         * fails the test at discovery time.
         *
         * <p>Since the capability list was inverted to name the models that DO take sampling params,
         * a newly synced model no longer needs adding here to be handled: anything recognised and
         * unlisted is assumed to take none. That is what closed the fable-5-1 gap.
         */
        @ParameterizedTest
        @EnumSource(value = AnthropicModelName.class, names = {"CLAUDE_SONNET_5", "CLAUDE_OPUS_4_7",
                "CLAUDE_OPUS_4_8", "CLAUDE_OPUS_5", "CLAUDE_FABLE_5",
                "CLAUDE_FABLE_5_1"}, mode = EnumSource.Mode.INCLUDE)
        void modelsTakingNoSamplingParamsRejectThem(AnthropicModelName model) {
            assertThat(ModelCapabilities.rejectsSamplingParams(model.getValue())).isTrue();
        }
    }

    @Nested
    @DisplayName("Temperature gating on the judge path")
    class TemperatureGating {

        @ParameterizedTest
        @ValueSource(strings = {"claude-sonnet-5", "claude-opus-4-8"})
        void temperatureIsNotForwardedForAdaptiveThinkingModels(String modelName) {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name(modelName)
                    .temperature(0.7)
                    .build());

            assertThat(parameters.temperature()).isNull();
        }

        @Test
        void temperatureIsForwardedForModelsThatSupportSamplingParams() {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-3-7-sonnet-20250219")
                    .temperature(0.7)
                    .build());

            assertThat(parameters.temperature()).isEqualTo(0.7);
        }

        @ParameterizedTest
        @ValueSource(strings = {"enabled", "adaptive", "some-future-mode"})
        void temperatureIsNotForwardedWhenThinkingNotDisabledOnSamplingCapableModel(String thinkingType) {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-3-7-sonnet-20250219")
                    .temperature(0.7)
                    .customParameters(JsonUtils.getJsonNodeFromString(
                            "{\"thinking\": {\"type\": \"%s\", \"budget_tokens\": 1024}}".formatted(thinkingType)))
                    .build());

            assertThat(parameters.temperature()).isNull();
        }

        @Test
        void temperatureIsForwardedWhenThinkingDisabledOnSamplingCapableModel() {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-3-7-sonnet-20250219")
                    .temperature(0.7)
                    .customParameters(JsonUtils.getJsonNodeFromString("{\"thinking\": {\"type\": \"disabled\"}}"))
                    .build());

            assertThat(parameters.temperature()).isEqualTo(0.7);
        }

        @ParameterizedTest
        @ValueSource(strings = {"{\"thinking\": {}}", "{\"thinking\": {\"type\": \"\"}}",
                "{\"thinking\": {\"budget_tokens\": 1024}}"})
        void temperatureIsForwardedWhenThinkingTypeMissingOrBlankOnSamplingCapableModel(String customParameters) {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-3-7-sonnet-20250219")
                    .temperature(0.7)
                    .customParameters(JsonUtils.getJsonNodeFromString(customParameters))
                    .build());

            assertThat(parameters.temperature()).isEqualTo(0.7);
        }
    }

    @Nested
    @DisplayName("max_tokens resolution on the judge path")
    class MaxTokensResolution {

        @Test
        void defaultsMaxTokensWhenNotProvided() {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-sonnet-5")
                    .build());

            assertThat(parameters.maxOutputTokens()).isEqualTo(DEFAULT_MAX_TOKENS);
        }

        @Test
        void forwardsMaxTokensFromCustomParameters() {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-sonnet-5")
                    .customParameters(JsonUtils.getJsonNodeFromString("{\"max_tokens\": 2048}"))
                    .build());

            assertThat(parameters.maxOutputTokens()).isEqualTo(2048);
        }

        @Test
        void maxTokensAddsHeadroomAboveThinkingBudgetWhenOnlyBudgetProvided() {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-sonnet-5")
                    .customParameters(JsonUtils.getJsonNodeFromString(
                            "{\"thinking\": {\"type\": \"enabled\", \"budget_tokens\": 4096}}"))
                    .build());

            assertThat(parameters.maxOutputTokens())
                    .isEqualTo(4096 + DEFAULT_MAX_TOKENS)
                    .isGreaterThan(parameters.thinkingBudgetTokens());
        }

        @Test
        void raisesExplicitMaxTokensThatDoesNotClearThinkingBudget() {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-sonnet-5")
                    .customParameters(JsonUtils.getJsonNodeFromString(
                            "{\"max_tokens\": 1000, \"thinking\": {\"type\": \"enabled\", \"budget_tokens\": 4096}}"))
                    .build());

            assertThat(parameters.maxOutputTokens())
                    .isEqualTo(4096 + DEFAULT_MAX_TOKENS)
                    .isGreaterThan(parameters.thinkingBudgetTokens());
        }

        @Test
        void honorsExplicitMaxTokensThatAlreadyClearsThinkingBudget() {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-sonnet-5")
                    .customParameters(JsonUtils.getJsonNodeFromString(
                            "{\"max_tokens\": 8000, \"thinking\": {\"type\": \"enabled\", \"budget_tokens\": 2048}}"))
                    .build());

            assertThat(parameters.maxOutputTokens()).isEqualTo(8000);
        }

        @Test
        void ignoresNonPositiveMaxTokensAndBudget() {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-sonnet-5")
                    .customParameters(JsonUtils.getJsonNodeFromString(
                            "{\"max_tokens\": 0, \"thinking\": {\"type\": \"enabled\", \"budget_tokens\": -5}}"))
                    .build());

            assertThat(parameters.maxOutputTokens()).isEqualTo(DEFAULT_MAX_TOKENS);
            assertThat(parameters.thinkingBudgetTokens()).isNull();
        }

        @Test
        void doesNotOverflowMaxTokensForExtremeThinkingBudget() {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-sonnet-5")
                    .customParameters(JsonUtils.getJsonNodeFromString(
                            "{\"thinking\": {\"type\": \"enabled\", \"budget_tokens\": 2147483647}}"))
                    .build());

            assertThat(parameters.maxOutputTokens()).isEqualTo(Integer.MAX_VALUE).isPositive();
        }
    }

    @Nested
    @DisplayName("thinking forwarding from custom_parameters")
    class ThinkingForwarding {

        @Test
        void forwardsThinkingDisabledFromCustomParameters() {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-sonnet-5")
                    .customParameters(JsonUtils.getJsonNodeFromString("{\"thinking\": {\"type\": \"disabled\"}}"))
                    .build());

            assertThat(parameters.thinkingType()).isEqualTo("disabled");
        }

        @ParameterizedTest
        @CsvSource({"enabled,1024", "enabled,4096"})
        void forwardsThinkingTypeAndBudgetFromCustomParameters(String type, int budget) {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-sonnet-5")
                    .customParameters(JsonUtils.getJsonNodeFromString(
                            "{\"thinking\": {\"type\": \"%s\", \"budget_tokens\": %d}}".formatted(type, budget)))
                    .build());

            assertThat(parameters.thinkingType()).isEqualTo(type);
            assertThat(parameters.thinkingBudgetTokens()).isEqualTo(budget);
        }

        @Test
        void leavesThinkingUnsetWhenNoCustomParameters() {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-sonnet-5")
                    .build());

            assertThat(parameters.thinkingType()).isNull();
        }

        @Test
        void dropsBudgetWhenThinkingTypeMissing() {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-sonnet-5")
                    .customParameters(JsonUtils.getJsonNodeFromString("{\"thinking\": {\"budget_tokens\": 1024}}"))
                    .build());

            assertThat(parameters.thinkingType()).isNull();
            assertThat(parameters.thinkingBudgetTokens()).isNull();
            assertThat(parameters.maxOutputTokens()).isEqualTo(DEFAULT_MAX_TOKENS);
        }

        @Test
        void dropsBudgetWhenThinkingDisabled() {
            var parameters = generateChatParameters(LlmAsJudgeModelParameters.builder()
                    .name("claude-sonnet-5")
                    .customParameters(JsonUtils.getJsonNodeFromString(
                            "{\"thinking\": {\"type\": \"disabled\", \"budget_tokens\": 1024}}"))
                    .build());

            assertThat(parameters.thinkingType()).isEqualTo("disabled");
            assertThat(parameters.thinkingBudgetTokens()).isNull();
            assertThat(parameters.maxOutputTokens()).isEqualTo(DEFAULT_MAX_TOKENS);
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("output_config.effort on the judge path")
    class EffortForwarding {

        private static final String MESSAGES_PATH = "/v1/messages";
        private static final String SCORE_NAME = "Correctness";
        private static final String MESSAGES_RESPONSE = """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-sonnet-4-6",\
                "content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn",\
                "usage":{"input_tokens":1,"output_tokens":1}}""";

        private WireMockServer wireMock;

        @BeforeAll
        void startWireMock() {
            wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
            wireMock.start();
        }

        @AfterAll
        void stopWireMock() {
            wireMock.stop();
        }

        @BeforeEach
        void stubMessages() {
            wireMock.resetAll();
            wireMock.stubFor(post(urlPathEqualTo(MESSAGES_PATH)).willReturn(okJson(MESSAGES_RESPONSE)));
        }

        @ParameterizedTest(name = "{0} at {1}")
        @CsvSource({"claude-sonnet-4-6, low", "claude-sonnet-5, xhigh", "claude-opus-5-5, max"})
        void sendsTheRuleEffortToAnthropic(String model, String effort) {
            var body = bodySentToAnthropic(model, "{\"output_config\": {\"effort\": \"%s\"}}".formatted(effort));

            assertThat(body.path("output_config"))
                    .isEqualTo(JsonUtils.getJsonNodeFromString("{\"effort\": \"%s\"}".formatted(effort)));
        }

        @Test
        void keepsTheOtherOutputConfigFieldsBesideTheEffort() {
            var outputConfig = """
                    {"effort": "low", "format": {"type": "json_schema",
                    "schema": {"type": "object", "properties": {"score": {"type": "number"}}}}}""";

            var body = bodySentToAnthropic("claude-sonnet-4-6", "{\"output_config\": %s}".formatted(outputConfig));

            assertThat(body.path("output_config")).isEqualTo(JsonUtils.getJsonNodeFromString(outputConfig));
        }

        @Test
        void sendsNoOutputConfigWhenTheRuleHasNoEffort() {
            var body = bodySentToAnthropic("claude-sonnet-4-6", "{\"max_tokens\": 2048}");

            assertThat(body.has("output_config")).isFalse();
            assertThat(body.path("max_tokens").asInt()).isEqualTo(2048);
        }

        @ParameterizedTest(name = "{0} at {1}")
        @CsvSource({"claude-sonnet-4-6, low", "claude-sonnet-4-6, max", "claude-opus-5-5, xhigh"})
        void keepsTheJudgeSchemaBesideTheEffortInOneOutputConfig(String model, String effort) {
            var rawBody = rawBodySentToAnthropic(model,
                    "{\"output_config\": {\"effort\": \"%s\"}}".formatted(effort), judgeRequest());
            var outputConfig = JsonUtils.getJsonNodeFromString(rawBody).path("output_config");

            assertThat(countOutputConfigKeys(rawBody)).isEqualTo(1);
            assertThat(outputConfig.path("effort").asText()).isEqualTo(effort);
            assertThat(outputConfig.path("format").path("type").asText()).isEqualTo("json_schema");
            assertThat(outputConfig.path("format").path("schema").path("properties").has(SCORE_NAME)).isTrue();
        }

        @Test
        void keepsTheOtherRuleOutputConfigFieldsWhenMergingTheJudgeSchema() {
            var rawBody = rawBodySentToAnthropic("claude-sonnet-4-6",
                    "{\"output_config\": {\"effort\": \"low\", \"future_option\": true}}", judgeRequest());
            var outputConfig = JsonUtils.getJsonNodeFromString(rawBody).path("output_config");

            assertThat(countOutputConfigKeys(rawBody)).isEqualTo(1);
            assertThat(outputConfig.path("effort").asText()).isEqualTo("low");
            assertThat(outputConfig.path("future_option").asBoolean()).isTrue();
            assertThat(outputConfig.path("format").path("type").asText()).isEqualTo("json_schema");
        }

        @Test
        void sendsOnlyTheJudgeSchemaWhenTheRuleHasNoEffort() {
            var rawBody = rawBodySentToAnthropic("claude-sonnet-4-6", "{\"max_tokens\": 2048}", judgeRequest());
            var outputConfig = JsonUtils.getJsonNodeFromString(rawBody).path("output_config");

            assertThat(countOutputConfigKeys(rawBody)).isEqualTo(1);
            assertThat(outputConfig.has("effort")).isFalse();
            assertThat(outputConfig.path("format").path("type").asText()).isEqualTo("json_schema");
        }

        @Test
        void sendsTheEffortOnceWhenAJudgeRequestUsesNoSchema() {
            var request = ChatRequest.builder().messages(UserMessage.from("hi")).build();

            var rawBody = rawBodySentToAnthropic("claude-sonnet-4-6", "{\"output_config\": {\"effort\": \"low\"}}",
                    request);

            assertThat(countOutputConfigKeys(rawBody)).isEqualTo(1);
            assertThat(JsonUtils.getJsonNodeFromString(rawBody).path("output_config"))
                    .isEqualTo(JsonUtils.getJsonNodeFromString("{\"effort\": \"low\"}"));
        }

        @Test
        void buildsOnlyTheModelAJudgeRequestNeedsAndReusesItOnARetry() {
            var built = new ArrayList<Map<String, Object>>();
            var chatModel = new AnthropicOutputConfigChatModel(Map.of("effort", "low"), customParameters -> {
                built.add(customParameters);
                return new ChatModel() {
                    @Override
                    public ChatResponse chat(ChatRequest chatRequest) {
                        return ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
                    }
                };
            });

            chatModel.chat(judgeRequest());
            chatModel.chat(judgeRequest());

            assertThat(built).hasSize(1);
            assertThat(built.getFirst().get("output_config")).asInstanceOf(InstanceOfAssertFactories.MAP)
                    .containsEntry("effort", "low")
                    .containsKey("format");
        }

        @ParameterizedTest(name = "{0} at {1}")
        @CsvSource({"claude-sonnet-4-6, adaptive", "claude-sonnet-4-6, xhigh", "claude-haiku-4-5-20251001, low"})
        void rejectsAnEffortTheModelDoesNotOfferBeforeCallingAnthropic(String model, String effort) {
            var modelParameters = LlmAsJudgeModelParameters.builder()
                    .name(model)
                    .customParameters(JsonUtils.getJsonNodeFromString(
                            "{\"output_config\": {\"effort\": \"%s\"}}".formatted(effort)))
                    .build();

            assertThatThrownBy(() -> generator.generateChat(wireMockConfig(), modelParameters))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("model '%s', effort '%s'".formatted(model, effort));
            assertThat(wireMock.findAll(postRequestedFor(urlPathEqualTo(MESSAGES_PATH)))).isEmpty();
        }

        private JsonNode bodySentToAnthropic(String model, String customParameters) {
            var chatModel = generator.generateChat(wireMockConfig(), LlmAsJudgeModelParameters.builder()
                    .name(model)
                    .customParameters(JsonUtils.getJsonNodeFromString(customParameters))
                    .build());

            chatModel.chat("hi");

            var sent = wireMock.findAll(postRequestedFor(urlPathEqualTo(MESSAGES_PATH)));
            assertThat(sent).hasSize(1);
            return JsonUtils.getJsonNodeFromString(sent.getFirst().getBodyAsString());
        }

        private ChatRequest judgeRequest() {
            List<ChatMessage> messages = List.of(UserMessage.from("Is Paris the capital of France?"));
            var schema = List.of(LlmAsJudgeOutputSchema.builder()
                    .name(SCORE_NAME)
                    .type(LlmAsJudgeOutputSchemaType.INTEGER)
                    .description("1 if correct")
                    .build());
            var builder = ChatRequest.builder().messages(messages);
            return new ToolCallingStrategy().apply(builder, messages, schema).build();
        }

        private String rawBodySentToAnthropic(String model, String customParameters, ChatRequest request) {
            var chatModel = generator.generateChat(wireMockConfig(), LlmAsJudgeModelParameters.builder()
                    .name(model)
                    .customParameters(JsonUtils.getJsonNodeFromString(customParameters))
                    .build());

            chatModel.chat(request);

            var sent = wireMock.findAll(postRequestedFor(urlPathEqualTo(MESSAGES_PATH)));
            assertThat(sent).hasSize(1);
            return sent.getFirst().getBodyAsString();
        }

        // Parsing would hide a duplicate: Jackson keeps only the last of two equal keys.
        private int countOutputConfigKeys(String rawBody) {
            return StringUtils.countMatches(rawBody, "\"output_config\"");
        }

        private LlmProviderClientApiConfig wireMockConfig() {
            return LlmProviderClientApiConfig.builder()
                    .apiKey("test-key")
                    .baseUrl(wireMock.baseUrl() + "/v1/")
                    .build();
        }
    }
}
