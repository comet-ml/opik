package com.comet.opik.domain;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.ExperimentExecutionRequest;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.langchain4j.OpikUserMessage;
import com.comet.opik.domain.template.MustacheParser;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.SystemMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("ExperimentMessageRenderer Test")
class ExperimentMessageRendererTest {

    private LlmProviderFactory llmProviderFactory;
    private ExperimentMessageRenderer renderer;

    @BeforeEach
    void setUp() {
        llmProviderFactory = Mockito.mock(LlmProviderFactory.class);
        renderer = new ExperimentMessageRenderer(new MustacheParser(), llmProviderFactory);
    }

    @Nested
    @DisplayName("buildTemplateContext")
    class BuildTemplateContext {

        @Test
        @DisplayName("should extract text values from dataset item data")
        void extractTextValues() {
            var data = Map.<String, JsonNode>of(
                    "name", new TextNode("Alice"),
                    "city", new TextNode("London"));
            var item = DatasetItem.builder().id(UUID.randomUUID()).data(data).build();

            var context = renderer.buildTemplateContext(item);

            assertThat(context).containsEntry("name", "Alice");
            assertThat(context).containsEntry("city", "London");
        }

        @Test
        @DisplayName("should convert non-text values to string")
        void convertNonTextValues() {
            var mapper = JsonUtils.getMapper();
            var data = Map.<String, JsonNode>of(
                    "count", mapper.valueToTree(42),
                    "active", mapper.valueToTree(true));
            var item = DatasetItem.builder().id(UUID.randomUUID()).data(data).build();

            var context = renderer.buildTemplateContext(item);

            assertThat(context).containsEntry("count", "42");
            assertThat(context).containsEntry("active", "true");
        }

        @Test
        @DisplayName("should return empty map when data is null")
        void returnEmptyMapWhenDataIsNull() {
            var item = DatasetItem.builder().id(UUID.randomUUID()).build();

            var context = renderer.buildTemplateContext(item);

            assertThat(context).isEmpty();
        }
    }

    @Nested
    @DisplayName("renderMessages")
    class RenderMessages {

        @Test
        @DisplayName("should substitute variables in text content")
        void substituteVariablesInTextContent() {
            var messages = List.of(
                    ExperimentExecutionRequest.PromptVariant.Message.builder()
                            .role("user")
                            .content(new TextNode("Hello {{name}}, welcome to {{city}}"))
                            .build());
            var context = Map.<String, Object>of("name", "Alice", "city", "London");

            var rendered = renderer.renderMessages(messages, context);

            assertThat(rendered).hasSize(1);
            assertThat(rendered.getFirst().content().asText()).isEqualTo("Hello Alice, welcome to London");
        }

        @Test
        @DisplayName("should substitute variables in array content text parts")
        void substituteVariablesInArrayContent() {
            var mapper = JsonUtils.getMapper();
            var arrayContent = mapper.createArrayNode();
            var textPart = mapper.createObjectNode();
            textPart.put("type", "text");
            textPart.put("text", "Hello {{name}}");
            arrayContent.add(textPart);

            var imagePart = mapper.createObjectNode();
            imagePart.put("type", "image_url");
            var imageUrl = mapper.createObjectNode();
            imageUrl.put("url", "https://example.com/img.png");
            imagePart.set("image_url", imageUrl);
            arrayContent.add(imagePart);

            var messages = List.of(
                    ExperimentExecutionRequest.PromptVariant.Message.builder()
                            .role("user")
                            .content(arrayContent)
                            .build());
            var context = Map.<String, Object>of("name", "Bob");

            var rendered = renderer.renderMessages(messages, context);

            assertThat(rendered).hasSize(1);
            var content = rendered.getFirst().content();
            assertThat(content.isArray()).isTrue();
            assertThat(content.get(0).get("text").asText()).isEqualTo("Hello Bob");
            assertThat(content.get(1).get("type").asText()).isEqualTo("image_url");
        }

        @Test
        @DisplayName("should substitute a variable in an image URL")
        void renderMessagesWhenImageUrlHasVariableSubstitutesIt() {
            var context = renderer.buildTemplateContext(datasetItem(Map.of(
                    "image", new TextNode("https://example.com/cat.png"),
                    "name", new TextNode("dog"))));

            var rendered = renderer.renderMessages(
                    List.of(userMessage(imagePart("{{image}}"), imagePart("https://cdn.example.com/{{name}}.png"))),
                    context);

            assertThat(imageUrls(rendered.getFirst().content()))
                    .containsExactly("https://example.com/cat.png", "https://cdn.example.com/dog.png");
        }

        static Stream<Arguments> listSources() {
            var urls = List.of("https://example.com/1.png", "data:image/png;base64,AAAA",
                    "https://example.com/3.png");
            var jsonArray = JsonUtils.valueToTree(urls);
            var csvText = new TextNode(jsonArray.toString());
            return Stream.of(
                    Arguments.of("JSON list", jsonArray, "{{images}}", urls),
                    Arguments.of("CSV text list", csvText, "{{images}}", urls),
                    Arguments.of("spaced tag", jsonArray, "{{ images }}", urls),
                    Arguments.of("triple braces", jsonArray, "{{{images}}}", urls),
                    Arguments.of("ampersand tag", jsonArray, "{{& images}}", urls),
                    Arguments.of("non-string items", JsonUtils.valueToTree(List.of(Map.of("url", "u"), 7)),
                            "{{images}}", List.of("{\"url\":\"u\"}", "7")));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("listSources")
        @DisplayName("should send one image per list item, in order, keeping the parts around it")
        void renderMessagesWhenVariableHoldsListExpandsOnePartPerItem(
                String caseName, JsonNode value, String template, List<String> expectedUrls) {
            var context = renderer.buildTemplateContext(datasetItem(Map.of("images", value)));
            var image = imagePart(template);
            ((ObjectNode) image.get("image_url")).put("detail", "low");

            var rendered = renderer.renderMessages(
                    List.of(userMessage(textPart("Classify these"), image, textPart("Answer as JSON"))), context);

            var content = rendered.getFirst().content();
            assertThat(content).hasSize(expectedUrls.size() + 2);
            assertThat(content.get(0).get("text").asText()).isEqualTo("Classify these");
            assertThat(content.get(content.size() - 1).get("text").asText()).isEqualTo("Answer as JSON");
            assertThat(imageUrls(content)).containsExactlyElementsOf(expectedUrls);
            assertThat(content.findValuesAsText("detail")).hasSize(expectedUrls.size()).containsOnly("low");
        }

        @Test
        @DisplayName("should drop the image part when the list is empty")
        void renderMessagesWhenListIsEmptyDropsThePart() {
            var context = renderer.buildTemplateContext(
                    datasetItem(Map.of("images", JsonUtils.getMapper().createArrayNode())));

            var rendered = renderer.renderMessages(
                    List.of(userMessage(textPart("Nothing to classify"), imagePart("{{images}}"))), context);

            var content = rendered.getFirst().content();
            assertThat(content).hasSize(1);
            assertThat(content.get(0).get("text").asText()).isEqualTo("Nothing to classify");
        }

        static Stream<Arguments> nonListValues() {
            return Stream.of(
                    Arguments.of("single URL", "{{image}}", new TextNode("https://example.com/1.png"),
                            "https://example.com/1.png"),
                    Arguments.of("text that only looks like a list", "{{image}}", new TextNode("[oops"), "[oops"),
                    Arguments.of("JSON object", "{{image}}", JsonUtils.valueToTree(Map.of("a", 1)), "{\"a\":1}"),
                    Arguments.of("list inside a longer URL", "https://cdn/{{image}}",
                            JsonUtils.valueToTree(List.of("a", "b")), "https://cdn/[\"a\",\"b\"]"),
                    Arguments.of("section", "{{#image}}x{{/image}}", new TextNode("[\"a\",\"b\"]"), "x"),
                    Arguments.of("missing variable", "{{other}}", new TextNode("unused"), ""));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("nonListValues")
        @DisplayName("should keep one image part when the template is not a single list variable")
        void renderMessagesWhenNotSingleListVariableKeepsOnePart(
                String caseName, String template, JsonNode value, String expectedUrl) {
            var context = renderer.buildTemplateContext(datasetItem(Map.of("image", value)));

            var rendered = renderer.renderMessages(List.of(userMessage(imagePart(template))), context);

            assertThat(imageUrls(rendered.getFirst().content())).containsExactly(expectedUrl);
        }

        @Test
        @DisplayName("should expand video and audio lists the same way")
        void renderMessagesWhenVideoAndAudioHoldListsExpandsThem() {
            var context = renderer.buildTemplateContext(datasetItem(Map.of(
                    "videos", JsonUtils.valueToTree(List.of("https://example.com/1.mp4", "https://example.com/2.mp4")),
                    "audios", JsonUtils.valueToTree(List.of("https://example.com/1.wav")))));

            var rendered = renderer.renderMessages(
                    List.of(userMessage(mediaPart("video_url", "{{videos}}"), mediaPart("audio_url", "{{audios}}"))),
                    context);

            assertThat(rendered.getFirst().content().findValuesAsText("url")).containsExactly(
                    "https://example.com/1.mp4", "https://example.com/2.mp4", "https://example.com/1.wav");
        }

        @Test
        @DisplayName("should put every list image into the provider request")
        void renderMessagesWhenListThenBuildsRequestWithEveryImage() {
            var context = renderer.buildTemplateContext(datasetItem(Map.of(
                    "images",
                    JsonUtils.valueToTree(List.of("https://example.com/1.png", "https://example.com/2.png")))));
            var rendered = renderer.renderMessages(
                    List.of(userMessage(textPart("Classify these"), imagePart("{{images}}"))), context);
            var prompt = new ExperimentExecutionRequest.PromptVariant("gpt-4o", rendered, null, null, null);

            var request = JsonUtils.valueToTree(renderer.buildChatCompletionRequest(prompt, rendered));

            var sentContent = request.get("messages").get(0).get("content");
            assertThat(sentContent.findValuesAsText("text")).containsExactly("Classify these");
            assertThat(sentContent.findValuesAsText("url"))
                    .containsExactly("https://example.com/1.png", "https://example.com/2.png");
        }

        @Test
        void buildChatCompletionRequestSendsVideoAndAudioAsMediaParts() {
            var rendered = renderer.renderMessages(List.of(userMessage(textPart("Describe these"),
                    mediaPart("video_url", "https://example.com/clip.mp4"),
                    mediaPart("audio_url", "https://example.com/voice.wav"))), Map.of());
            var prompt = new ExperimentExecutionRequest.PromptVariant("gemini-3.8-flash", rendered, null, null, null);

            var request = JsonUtils.valueToTree(renderer.buildChatCompletionRequest(prompt, rendered));

            var sentContent = request.get("messages").get(0).get("content");
            assertThat(sentContent.findValuesAsText("type")).containsExactly("text", "video_url", "audio_url");
            assertThat(sentContent.findValuesAsText("text")).containsExactly("Describe these");
            assertThat(sentContent.findValuesAsText("url"))
                    .containsExactly("https://example.com/clip.mp4", "https://example.com/voice.wav");
        }

        @Test
        @DisplayName("should return message unchanged when content is neither text nor array")
        void returnUnchangedForOtherContentTypes() {
            var objectContent = JsonUtils.createObjectNode();
            objectContent.put("key", "value");

            var message = ExperimentExecutionRequest.PromptVariant.Message.builder()
                    .role("user")
                    .content(objectContent)
                    .build();

            var rendered = renderer.renderMessages(List.of(message), Map.of());

            assertThat(rendered).hasSize(1);
            assertThat(rendered.getFirst()).isSameAs(message);
        }

        private DatasetItem datasetItem(Map<String, JsonNode> data) {
            return DatasetItem.builder().id(UUID.randomUUID()).data(data).build();
        }

        private ExperimentExecutionRequest.PromptVariant.Message userMessage(JsonNode... parts) {
            var content = JsonUtils.getMapper().createArrayNode();
            for (JsonNode part : parts) {
                content.add(part);
            }
            return ExperimentExecutionRequest.PromptVariant.Message.builder().role("user").content(content).build();
        }

        private ObjectNode textPart(String text) {
            return JsonUtils.createObjectNode().put("type", "text").put("text", text);
        }

        private ObjectNode imagePart(String url) {
            return mediaPart("image_url", url);
        }

        private ObjectNode mediaPart(String type, String url) {
            var part = JsonUtils.createObjectNode().put("type", type);
            part.set(type, JsonUtils.createObjectNode().put("url", url));
            return part;
        }

        private List<String> imageUrls(JsonNode content) {
            var urls = new ArrayList<String>();
            content.forEach(part -> {
                if (part.has("image_url")) {
                    urls.add(part.get("image_url").get("url").asText());
                }
            });
            return urls;
        }
    }

    @Nested
    @DisplayName("buildChatCompletionRequest")
    class BuildChatCompletionRequest {

        @Test
        @DisplayName("should build request with correct model and messages")
        void buildRequestWithModelAndMessages() {
            var messages = List.of(
                    ExperimentExecutionRequest.PromptVariant.Message.builder()
                            .role("system")
                            .content(new TextNode("You are helpful"))
                            .build(),
                    ExperimentExecutionRequest.PromptVariant.Message.builder()
                            .role("user")
                            .content(new TextNode("Hello"))
                            .build());

            var prompt = new ExperimentExecutionRequest.PromptVariant(
                    "gpt-4o", messages, null, null, null);

            ChatCompletionRequest request = renderer.buildChatCompletionRequest(prompt, messages);

            assertThat(request.model()).isEqualTo("gpt-4o");
            assertThat(request.messages()).hasSize(2);
            assertThat(request.messages().getFirst()).isInstanceOf(SystemMessage.class);
            assertThat(request.messages().getLast()).isInstanceOf(OpikUserMessage.class);
            assertThat(request.stream()).isFalse();
        }

        @Test
        @DisplayName("should apply config parameters when provided")
        void applyConfigParameters() {
            var messages = List.of(
                    ExperimentExecutionRequest.PromptVariant.Message.builder()
                            .role("user")
                            .content(new TextNode("Hello"))
                            .build());

            var mapper = JsonUtils.getMapper();
            var configs = Map.<String, JsonNode>of(
                    "temperature", mapper.valueToTree(0.7),
                    "topP", mapper.valueToTree(0.9),
                    "maxCompletionTokens", mapper.valueToTree(1024),
                    "frequencyPenalty", mapper.valueToTree(0.5),
                    "presencePenalty", mapper.valueToTree(0.3));

            var prompt = new ExperimentExecutionRequest.PromptVariant(
                    "gpt-4o", messages, configs, null, null);

            ChatCompletionRequest request = renderer.buildChatCompletionRequest(prompt, messages);

            assertThat(request.temperature()).isEqualTo(0.7);
            assertThat(request.topP()).isEqualTo(0.9);
            assertThat(request.maxCompletionTokens()).isEqualTo(1024);
            assertThat(request.frequencyPenalty()).isEqualTo(0.5);
            assertThat(request.presencePenalty()).isEqualTo(0.3);
            // configs present but carrying no custom_parameters key must not invent one
            assertThat(request.customParameters()).isNull();
        }

        @Test
        @DisplayName("should not recognize snake_case config keys - frontend must send camelCase")
        void rejectSnakeCaseConfigKeys() {
            var messages = List.of(
                    ExperimentExecutionRequest.PromptVariant.Message.builder()
                            .role("user")
                            .content(new TextNode("Hello"))
                            .build());

            var mapper = JsonUtils.getMapper();
            var configs = Map.<String, JsonNode>of(
                    "temperature", mapper.valueToTree(0.7),
                    "top_p", mapper.valueToTree(0.9),
                    "max_completion_tokens", mapper.valueToTree(1024),
                    "frequency_penalty", mapper.valueToTree(0.5),
                    "presence_penalty", mapper.valueToTree(0.3));

            var prompt = new ExperimentExecutionRequest.PromptVariant(
                    "gpt-4o", messages, configs, null, null);

            ChatCompletionRequest request = renderer.buildChatCompletionRequest(prompt, messages);

            assertThat(request.temperature()).isEqualTo(0.7);
            assertThat(request.topP()).as("top_p should not be recognized, must use topP").isNull();
            assertThat(request.maxCompletionTokens())
                    .as("max_completion_tokens should not be recognized, must use maxCompletionTokens").isNull();
            assertThat(request.frequencyPenalty())
                    .as("frequency_penalty should not be recognized, must use frequencyPenalty").isNull();
            assertThat(request.presencePenalty())
                    .as("presence_penalty should not be recognized, must use presencePenalty").isNull();
        }

        @Test
        @DisplayName("should not apply configs when null")
        void noConfigsWhenNull() {
            var messages = List.of(
                    ExperimentExecutionRequest.PromptVariant.Message.builder()
                            .role("user")
                            .content(new TextNode("Hello"))
                            .build());

            var prompt = new ExperimentExecutionRequest.PromptVariant(
                    "gpt-4o", messages, null, null, null);

            ChatCompletionRequest request = renderer.buildChatCompletionRequest(prompt, messages);

            assertThat(request.temperature()).isNull();
            assertThat(request.topP()).isNull();
            assertThat(request.maxCompletionTokens()).isNull();
        }

        @Test
        @DisplayName("should send every parameter of the config the OpenRouter panel sends")
        void applyOpenRouterConfigs() {
            var messages = List.of(
                    ExperimentExecutionRequest.PromptVariant.Message.builder()
                            .role("user")
                            .content(new TextNode("Hello"))
                            .build());

            var mapper = JsonUtils.getMapper();
            var configs = Map.<String, JsonNode>of(
                    "maxTokens", mapper.valueToTree(512),
                    "temperature", mapper.valueToTree(0.7),
                    "topP", mapper.valueToTree(0.9),
                    "frequencyPenalty", mapper.valueToTree(0.5),
                    "presencePenalty", mapper.valueToTree(0.3),
                    "custom_parameters", JsonUtils.getJsonNodeFromString(
                            "{\"top_k\": 40, \"min_p\": 0.1, \"top_a\": 0.2, \"repetition_penalty\": 1.1}"));

            var prompt = new ExperimentExecutionRequest.PromptVariant(
                    "openai/gpt-4o", messages, configs, null, null);

            ChatCompletionRequest request = renderer.buildChatCompletionRequest(prompt, messages);

            assertThat(request).isEqualTo(ChatCompletionRequest.builder()
                    .model("openai/gpt-4o")
                    .messages(request.messages())
                    .stream(false)
                    .maxTokens(512)
                    .temperature(0.7)
                    .topP(0.9)
                    .frequencyPenalty(0.5)
                    .presencePenalty(0.3)
                    .customParameters(Map.of("top_k", 40, "min_p", 0.1, "top_a", 0.2, "repetition_penalty", 1.1))
                    .build());
        }

        @ParameterizedTest
        @ValueSource(strings = {"0", "\"512\"", "null"})
        @DisplayName("should send no max_tokens for 0, which the OpenRouter panel uses for no limit, or a non-number")
        void skipMaxTokensForZeroOrNonNumericValue(String json) {
            var messages = List.of(
                    ExperimentExecutionRequest.PromptVariant.Message.builder()
                            .role("user")
                            .content(new TextNode("Hello"))
                            .build());

            var prompt = new ExperimentExecutionRequest.PromptVariant(
                    "openai/gpt-4o", messages, Map.of("maxTokens", JsonUtils.getJsonNodeFromString(json)), null,
                    null);

            assertThat(renderer.buildChatCompletionRequest(prompt, messages).maxTokens()).isNull();
        }

        @Test
        @DisplayName("should forward custom_parameters so provider-specific settings reach the request")
        void forwardCustomParameters() {
            var messages = List.of(
                    ExperimentExecutionRequest.PromptVariant.Message.builder()
                            .role("user")
                            .content(new TextNode("Hello"))
                            .build());

            // Snake_case by design, unlike the flat numeric configs above: this is the wire name on
            // ChatCompletionRequest itself, which is what the playground body already uses.
            var configs = Map.<String, JsonNode>of(
                    "custom_parameters",
                    JsonUtils.getJsonNodeFromString("{\"thinking\": {\"level\": \"high\"}}"));

            var prompt = new ExperimentExecutionRequest.PromptVariant(
                    "gemini-2.5-flash-lite", messages, configs, null, null);

            ChatCompletionRequest request = renderer.buildChatCompletionRequest(prompt, messages);

            assertThat(request.customParameters())
                    .isEqualTo(Map.of("thinking", Map.of("level", "high")));
        }

        @ParameterizedTest
        @ValueSource(strings = {"[1, 2]", "\"text\"", "5", "true", "null"})
        @DisplayName("should ignore custom_parameters of any non-object shape rather than failing")
        void ignoreEveryNonObjectCustomParameterShape(String json) {
            var messages = List.of(
                    ExperimentExecutionRequest.PromptVariant.Message.builder()
                            .role("user")
                            .content(new TextNode("Hello"))
                            .build());

            var prompt = new ExperimentExecutionRequest.PromptVariant(
                    "gemini-2.5-flash-lite", messages,
                    Map.of("custom_parameters", JsonUtils.getJsonNodeFromString(json)), null, null);

            assertThat(renderer.buildChatCompletionRequest(prompt, messages).customParameters()).isNull();
        }

        @Test
        @DisplayName("should ignore custom_parameters that is not an object rather than failing")
        void ignoreNonObjectCustomParameters() {
            var messages = List.of(
                    ExperimentExecutionRequest.PromptVariant.Message.builder()
                            .role("user")
                            .content(new TextNode("Hello"))
                            .build());

            var configs = Map.<String, JsonNode>of(
                    "custom_parameters", JsonUtils.getJsonNodeFromString("[1, 2]"));

            var prompt = new ExperimentExecutionRequest.PromptVariant(
                    "gemini-2.5-flash-lite", messages, configs, null, null);

            ChatCompletionRequest request = renderer.buildChatCompletionRequest(prompt, messages);

            assertThat(request.customParameters()).isNull();
        }
    }

    @Nested
    @DisplayName("buildChatCompletionRequest reasoning effort")
    class ReasoningEffort {

        private static final String OPENAI_MODEL = "gpt-5.5";
        private static final List<ExperimentExecutionRequest.PromptVariant.Message> MESSAGES = List.of(
                ExperimentExecutionRequest.PromptVariant.Message.builder()
                        .role("user")
                        .content(new TextNode("Hello"))
                        .build());

        private ChatCompletionRequest buildWithEffort(String model, JsonNode effort) {
            var prompt = new ExperimentExecutionRequest.PromptVariant(
                    model, MESSAGES, Map.of("reasoningEffort", effort), null, null);
            return renderer.buildChatCompletionRequest(prompt, MESSAGES);
        }

        @ParameterizedTest
        @ValueSource(strings = {"none", "low", "high", "xhigh", "max"})
        @DisplayName("should forward the stored effort for an OpenAI model")
        void forwardReasoningEffortForOpenAi(String effort) {
            when(llmProviderFactory.getLlmProvider(OPENAI_MODEL)).thenReturn(LlmProvider.OPEN_AI);

            var request = buildWithEffort(OPENAI_MODEL, new TextNode(effort));

            assertThat(request.reasoningEffort()).isEqualTo(effort);
        }

        @ParameterizedTest
        @EnumSource(value = LlmProvider.class, names = "OPEN_AI", mode = EnumSource.Mode.EXCLUDE)
        @DisplayName("should not forward a stored effort to any other provider")
        void dropReasoningEffortForOtherProviders(LlmProvider provider) {
            var model = "some-" + provider.getValue() + "-model";
            when(llmProviderFactory.getLlmProvider(model)).thenReturn(provider);

            var request = buildWithEffort(model, new TextNode("high"));

            assertThat(request.reasoningEffort()).isNull();
        }

        @ParameterizedTest
        @ValueSource(strings = {"\"\"", "\"  \"", "5", "null", "{\"effort\": \"high\"}"})
        @DisplayName("should leave the effort unset when the stored value is blank or not a string")
        void ignoreBlankOrNonTextualReasoningEffort(String json) {
            var request = buildWithEffort(OPENAI_MODEL, JsonUtils.getJsonNodeFromString(json));

            assertThat(request.reasoningEffort()).isNull();
            verifyNoInteractions(llmProviderFactory);
        }

        @Test
        @DisplayName("should leave the effort unset when the configs carry none")
        void noReasoningEffortWhenAbsent() {
            var prompt = new ExperimentExecutionRequest.PromptVariant(
                    OPENAI_MODEL, MESSAGES, Map.of("temperature", JsonUtils.getMapper().valueToTree(0.7)), null,
                    null);

            var request = renderer.buildChatCompletionRequest(prompt, MESSAGES);

            assertThat(request.reasoningEffort()).isNull();
            verifyNoInteractions(llmProviderFactory);
        }
    }
}
