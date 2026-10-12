package com.comet.opik.infrastructure.llm;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode;
import com.comet.opik.api.resources.v1.events.OnlineScoringEngine;
import com.comet.opik.domain.LlmProviderApiKeyService;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.infrastructure.EncryptionUtils;
import com.comet.opik.infrastructure.FreeModelConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.llm.antropic.AnthropicModule;
import com.comet.opik.utils.ChunkedOutputHandlers;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The exact JSON body each provider receives, one row per rule: (provider, model, settings) → body. Rows live in
 * {@code src/test/resources/wire-body/<provider>.yaml} and pin what main sends today, so moving a model rule into the
 * catalog can be checked to change nothing. Requests go through {@link ChatCompletionService} with the real provider
 * factory, so every transformation between the API and the provider runs; only the provider is replaced.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WireBodyTableTest {

    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String JUDGE_PATH = "judge";
    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());
    // Parsing would otherwise hide a duplicate key: Jackson keeps only the last of two.
    private static final ObjectMapper STRICT_MAPPER = JsonUtils.getMapper().copy()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    private final WireMockServer wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
    private LlmProviderFactoryImpl llmProviderFactory;
    private ChatCompletionService chatCompletionService;

    @RequiredArgsConstructor
    private enum Provider {
        ANTHROPIC(LlmProvider.ANTHROPIC, "anthropic", "/v1/", "/v1/messages", """
                {"id":"msg_1","type":"message","role":"assistant","model":"claude",\
                "content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn",\
                "usage":{"input_tokens":1,"output_tokens":1}}""", """
                event: message_start
                data: {"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant",\
                "model":"claude","content":[],"stop_reason":null,"usage":{"input_tokens":1,"output_tokens":1}}}

                event: content_block_start
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}

                event: content_block_delta
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"ok"}}

                event: content_block_stop
                data: {"type":"content_block_stop","index":0}

                event: message_delta
                data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":1}}

                event: message_stop
                data: {"type":"message_stop"}

                """);

        private final LlmProvider llmProvider;
        private final String rowsFile;
        private final String baseUrlPath;
        private final String endpointPath;
        private final String response;
        private final String streamResponse;
    }

    private record Row(String model, String path, JsonNode request, JsonNode body, String rejected) {
    }

    @BeforeAll
    void setUpAll() {
        wireMock.start();

        var config = TestConfigUtils.loadConfigTest();
        EncryptionUtils.setConfig(config);
        var registryConfig = config.getLlmModelRegistry();
        registryConfig.setDefaultResource("wire-body/llm-models.yaml");

        var llmProviderApiKeyService = mock(LlmProviderApiKeyService.class);
        when(llmProviderApiKeyService.findByProviders(eq(WORKSPACE_ID), anySet()))
                .thenReturn(Arrays.stream(Provider.values()).map(this::providerApiKey).toList());

        var freeModelConfig = new FreeModelConfig();
        freeModelConfig.setEnabled(false);
        var opikConfiguration = mock(OpikConfiguration.class);
        when(opikConfiguration.getFreeModel()).thenReturn(freeModelConfig);

        llmProviderFactory = new LlmProviderFactoryImpl(llmProviderApiKeyService, opikConfiguration,
                new LlmModelRegistryService(registryConfig));
        var anthropicModule = new AnthropicModule();
        anthropicModule.llmServiceProvider(llmProviderFactory,
                anthropicModule.clientGenerator(config.getLlmProviderClient()));

        chatCompletionService = new ChatCompletionService(config.getLlmProviderClient(), llmProviderFactory);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.stop();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void anthropicSends(String name, Row row) {
        assertSends(Provider.ANTHROPIC, row);
    }

    private Stream<Arguments> anthropicSends() {
        return rows(Provider.ANTHROPIC, false);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    void anthropicRejects(String name, Row row) {
        assertRejects(Provider.ANTHROPIC, row);
    }

    private Stream<Arguments> anthropicRejects() {
        return rows(Provider.ANTHROPIC, true);
    }

    private void assertSends(Provider provider, Row row) {
        if (JUDGE_PATH.equals(row.path())) {
            stubProvider(provider, false);
            scoreTrace(row);
        } else {
            var request = JsonUtils.treeToValue(row.request(), ChatCompletionRequest.class);
            var stream = Boolean.TRUE.equals(request.stream());
            stubProvider(provider, stream);
            if (stream) {
                var handlers = mock(ChunkedOutputHandlers.class);
                chatCompletionService.createAndStreamResponse(request, WORKSPACE_ID, handlers);
                verify(handlers, timeout(5_000)).handleClose();
                verify(handlers, never()).handleError(any());
            } else {
                chatCompletionService.create(request, WORKSPACE_ID);
            }
        }

        var sent = wireMock.getAllServeEvents();
        assertThat(sent).hasSize(1);
        assertThat(sent.getFirst().getRequest().getUrl()).isEqualTo(provider.endpointPath);
        assertThat(readStrict(sent.getFirst().getRequest().getBodyAsString())).isEqualTo(row.body());
    }

    private void assertRejects(Provider provider, Row row) {
        stubProvider(provider, false);

        assertThatThrownBy(() -> {
            if (JUDGE_PATH.equals(row.path())) {
                scoreTrace(row);
            } else {
                chatCompletionService.create(JsonUtils.treeToValue(row.request(), ChatCompletionRequest.class),
                        WORKSPACE_ID);
            }
        }).hasMessageContaining(row.rejected());
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    private void stubProvider(Provider provider, boolean stream) {
        wireMock.resetAll();
        wireMock.stubFor(post(urlPathEqualTo(provider.endpointPath)).willReturn(stream
                ? aResponse().withHeader("Content-Type", MediaType.SERVER_SENT_EVENTS).withBody(provider.streamResponse)
                : okJson(provider.response)));
    }

    private void scoreTrace(Row row) {
        var code = JsonUtils.treeToValue(row.request(), LlmAsJudgeCode.class);
        var chatRequest = OnlineScoringEngine.prepareLlmRequest(code, null,
                llmProviderFactory.getStructuredOutputStrategy(row.model()), List.of());
        chatCompletionService.scoreTrace(chatRequest, code.model(), WORKSPACE_ID);
    }

    private ProviderApiKey providerApiKey(Provider provider) {
        return ProviderApiKey.builder()
                .provider(provider.llmProvider)
                .apiKey(EncryptionUtils.encrypt("test-key"))
                .baseUrl(wireMock.baseUrl() + provider.baseUrlPath)
                .build();
    }

    /**
     * A row's request and body are merged over the file's defaults for its path: a key the row sets wins, and a
     * key the row sets to null is removed. A row runs once per model in {@code models}, where a name from the file's
     * {@code models} section stands for every model in that group.
     */
    @SneakyThrows
    private Stream<Arguments> rows(Provider provider, boolean rejected) {
        JsonNode file;
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("wire-body/%s.yaml".formatted(provider.rowsFile))) {
            file = YAML_MAPPER.readTree(is);
        }
        var arguments = new ArrayList<Arguments>();
        for (var row : file.get("rows")) {
            if (row.hasNonNull("rejected") != rejected) {
                continue;
            }
            var path = row.path("path").asText("chat");
            var defaults = file.path("defaults").path(path);
            for (var model : models(file.path("models"), row)) {
                var body = merge(defaults.path("body"), row.path("body"));
                ((ObjectNode) body).put("model", model);
                arguments.add(arguments("%s [%s]".formatted(row.get("name").asText(), model),
                        new Row(model, path, request(path, defaults, row, model), toStrictJson(body),
                                row.hasNonNull("rejected") ? row.get("rejected").asText() : null)));
            }
        }
        return arguments.stream();
    }

    private static List<String> models(JsonNode groups, JsonNode row) {
        Iterable<JsonNode> entries = row.has("models") ? row.get("models") : List.of(row.get("model"));
        var models = new ArrayList<String>();
        for (var entry : entries) {
            if (groups.has(entry.asText())) {
                groups.get(entry.asText()).forEach(model -> models.add(model.asText()));
            } else {
                models.add(entry.asText());
            }
        }
        return models;
    }

    private JsonNode request(String path, JsonNode defaults, JsonNode row, String model) {
        if (JUDGE_PATH.equals(path)) {
            var modelParameters = merge(defaults.path("rule").path("model"), row.path("request"));
            ((ObjectNode) modelParameters).put("name", model);
            var rule = defaults.path("rule").deepCopy();
            ((ObjectNode) rule).set("model", modelParameters);
            return rule;
        }
        var request = merge(defaults.path("request"), row.path("request"));
        ((ObjectNode) request).put("model", model);
        return request;
    }

    private static JsonNode merge(JsonNode base, JsonNode overrides) {
        if (!overrides.isObject()) {
            return base.isObject() ? base.deepCopy() : JsonUtils.createObjectNode();
        }
        var result = base.isObject() ? (ObjectNode) base.deepCopy() : JsonUtils.createObjectNode();
        overrides.properties().forEach(entry -> {
            if (entry.getValue().isNull()) {
                result.remove(entry.getKey());
            } else if (entry.getValue().isObject() && result.path(entry.getKey()).isObject()) {
                result.set(entry.getKey(), merge(result.get(entry.getKey()), entry.getValue()));
            } else {
                result.set(entry.getKey(), entry.getValue().deepCopy());
            }
        });
        return result;
    }

    // YAML reads 0.5 as a double and the provider's JSON may not; reading both through one mapper compares like with like.
    private static JsonNode toStrictJson(JsonNode node) {
        return readStrict(node.toString());
    }

    @SneakyThrows(IOException.class)
    private static JsonNode readStrict(String json) {
        return STRICT_MAPPER.readTree(json);
    }
}
