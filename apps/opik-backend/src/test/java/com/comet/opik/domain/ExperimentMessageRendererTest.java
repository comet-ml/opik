package com.comet.opik.domain;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.ExperimentExecutionRequest;
import com.comet.opik.domain.llm.langchain4j.OpikUserMessage;
import com.comet.opik.domain.template.MustacheParser;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.SystemMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ExperimentMessageRenderer Test")
class ExperimentMessageRendererTest {

    private ExperimentMessageRenderer renderer;

    @BeforeEach
    void setUp() {
        renderer = new ExperimentMessageRenderer(new MustacheParser());
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
                    "gpt-4o", messages, null, null);

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
                    "gpt-4o", messages, configs, null);

            ChatCompletionRequest request = renderer.buildChatCompletionRequest(prompt, messages);

            assertThat(request.temperature()).isEqualTo(0.7);
            assertThat(request.topP()).isEqualTo(0.9);
            assertThat(request.maxCompletionTokens()).isEqualTo(1024);
            assertThat(request.frequencyPenalty()).isEqualTo(0.5);
            assertThat(request.presencePenalty()).isEqualTo(0.3);
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
                    "gpt-4o", messages, configs, null);

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
                    "gpt-4o", messages, null, null);

            ChatCompletionRequest request = renderer.buildChatCompletionRequest(prompt, messages);

            assertThat(request.temperature()).isNull();
            assertThat(request.topP()).isNull();
            assertThat(request.maxCompletionTokens()).isNull();
        }
    }
}
