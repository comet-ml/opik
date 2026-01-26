package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.LlmAsJudgeMessage;
import com.comet.opik.domain.SpanType;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OnlineScoringEngine - Auto-Extract Variable Mode")
class OnlineScoringEngineDirectMustacheTest {

    @Test
    @DisplayName("Auto-extract mode: simple field access")
    void testAutoExtractSimpleFields() {
        // Given
        JsonNode input = JsonUtils.getMapper().valueToTree(Map.of(
                "question", "What is AI?"
        ));
        JsonNode output = JsonUtils.getMapper().valueToTree(Map.of(
                "answer", "AI is Artificial Intelligence"
        ));

        Trace trace = Trace.builder()
                .id(UUID.randomUUID())
                .input(input)
                .output(output)
                .build();

        LlmAsJudgeMessage message = LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .content("Question: {{input.question}}\nAnswer: {{output.answer}}")
                .build();

        // When - null variables triggers auto-extract mode
        List<ChatMessage> result = OnlineScoringEngine.renderMessages(
                List.of(message), null, trace);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOf(UserMessage.class);
        String rendered = ((UserMessage) result.get(0)).singleText();
        assertThat(rendered).contains("Question: What is AI?");
        assertThat(rendered).contains("Answer: AI is Artificial Intelligence");
    }

    @Test
    @DisplayName("Auto-extract mode: array indexing with JSONPath")
    void testAutoExtractArrayIndexing() {
        // Given - LangChain-style messages array
        JsonNode input = JsonUtils.getMapper().valueToTree(Map.of(
                "messages", List.of(
                        Map.of("role", "user", "content", "What is AI?"),
                        Map.of("role", "assistant", "content", "AI is Artificial Intelligence")
                )
        ));

        Trace trace = Trace.builder()
                .id(UUID.randomUUID())
                .input(input)
                .build();

        LlmAsJudgeMessage message = LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .content("First message: {{input.messages[0].content}}\nSecond message: {{input.messages[1].content}}")
                .build();

        // When
        List<ChatMessage> result = OnlineScoringEngine.renderMessages(
                List.of(message), null, trace);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOf(UserMessage.class);
        String rendered = ((UserMessage) result.get(0)).singleText();
        assertThat(rendered).contains("First message: What is AI?");
        assertThat(rendered).contains("Second message: AI is Artificial Intelligence");
    }

    @Test
    @DisplayName("Auto-extract mode: nested object access")
    void testAutoExtractNestedObjects() {
        // Given
        JsonNode input = JsonUtils.getMapper().valueToTree(Map.of(
                "user", Map.of(
                        "profile", Map.of(
                                "name", "John Doe",
                                "email", "john@example.com"
                        )
                )
        ));

        Trace trace = Trace.builder()
                .id(UUID.randomUUID())
                .input(input)
                .build();

        LlmAsJudgeMessage message = LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .content("User: {{input.user.profile.name}} ({{input.user.profile.email}})")
                .build();

        // When
        List<ChatMessage> result = OnlineScoringEngine.renderMessages(
                List.of(message), null, trace);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOf(UserMessage.class);
        String rendered = ((UserMessage) result.get(0)).singleText();
        assertThat(rendered).isEqualTo("User: John Doe (john@example.com)");
    }

    @Test
    @DisplayName("Auto-extract mode: complex nested arrays")
    void testAutoExtractComplexNestedArrays() {
        // Given
        JsonNode input = JsonUtils.getMapper().valueToTree(Map.of(
                "conversation", List.of(
                        Map.of(
                                "user", Map.of("name", "Alice"),
                                "message", "Hello"
                        ),
                        Map.of(
                                "user", Map.of("name", "Bob"),
                                "message", "Hi there"
                        )
                )
        ));

        Trace trace = Trace.builder()
                .id(UUID.randomUUID())
                .input(input)
                .build();

        LlmAsJudgeMessage message = LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .content("{{input.conversation[0].user.name}}: {{input.conversation[0].message}}\n{{input.conversation[1].user.name}}: {{input.conversation[1].message}}")
                .build();

        // When
        List<ChatMessage> result = OnlineScoringEngine.renderMessages(
                List.of(message), null, trace);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOf(UserMessage.class);
        String rendered = ((UserMessage) result.get(0)).singleText();
        assertThat(rendered).contains("Alice: Hello");
        assertThat(rendered).contains("Bob: Hi there");
    }

    @Test
    @DisplayName("Auto-extract mode: multiple variables from different sections")
    void testAutoExtractMultipleSections() {
        // Given
        JsonNode input = JsonUtils.getMapper().valueToTree(Map.of("query", "test query"));
        JsonNode output = JsonUtils.getMapper().valueToTree(Map.of("result", "test result"));
        JsonNode metadata = JsonUtils.getMapper().valueToTree(Map.of("model", "gpt-4"));

        Trace trace = Trace.builder()
                .id(UUID.randomUUID())
                .input(input)
                .output(output)
                .metadata(metadata)
                .build();

        LlmAsJudgeMessage message = LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .content("Query: {{input.query}}\nResult: {{output.result}}\nModel: {{metadata.model}}")
                .build();

        // When
        List<ChatMessage> result = OnlineScoringEngine.renderMessages(
                List.of(message), null, trace);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOf(UserMessage.class);
        String rendered = ((UserMessage) result.get(0)).singleText();
        assertThat(rendered).contains("Query: test query");
        assertThat(rendered).contains("Result: test result");
        assertThat(rendered).contains("Model: gpt-4");
    }

    @Test
    @DisplayName("Auto-extract mode: works with Span")
    void testAutoExtractWithSpan() {
        // Given
        JsonNode input = JsonUtils.getMapper().valueToTree(Map.of("query", "search term"));
        JsonNode output = JsonUtils.getMapper().valueToTree(Map.of("results", List.of("result1", "result2")));

        Span span = Span.builder()
                .id(UUID.randomUUID())
                .type(SpanType.llm)
                .input(input)
                .output(output)
                .build();

        LlmAsJudgeMessage message = LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .content("Search: {{input.query}}\nFirst result: {{output.results[0]}}")
                .build();

        // When
        List<ChatMessage> result = OnlineScoringEngine.renderMessages(
                List.of(message), null, span);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOf(UserMessage.class);
        String rendered = ((UserMessage) result.get(0)).singleText();
        assertThat(rendered).contains("Search: search term");
        assertThat(rendered).contains("First result: result1");
    }

    @Test
    @DisplayName("Variable mapping mode: still works with explicit variables")
    void testLegacyVariableMappingMode() {
        // Given
        JsonNode input = JsonUtils.getMapper().valueToTree(Map.of("question", "What is AI?"));
        JsonNode output = JsonUtils.getMapper().valueToTree(Map.of("answer", "AI is Artificial Intelligence"));

        Trace trace = Trace.builder()
                .id(UUID.randomUUID())
                .input(input)
                .output(output)
                .build();

        LlmAsJudgeMessage message = LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .content("Q: {{q}}\nA: {{a}}")
                .build();

        Map<String, String> variables = Map.of(
                "q", "input.question",
                "a", "output.answer"
        );

        // When - providing variables uses variable mapping mode
        List<ChatMessage> result = OnlineScoringEngine.renderMessages(
                List.of(message), variables, trace);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOf(UserMessage.class);
        String rendered = ((UserMessage) result.get(0)).singleText();
        assertThat(rendered).contains("Q: What is AI?");
        assertThat(rendered).contains("A: AI is Artificial Intelligence");
    }

    @Test
    @DisplayName("Auto-extract mode: handles missing fields gracefully")
    void testAutoExtractMissingFields() {
        // Given - trace with only input
        JsonNode input = JsonUtils.getMapper().valueToTree(Map.of("question", "What is AI?"));

        Trace trace = Trace.builder()
                .id(UUID.randomUUID())
                .input(input)
                .build();

        LlmAsJudgeMessage message = LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .content("Q: {{input.question}}\nA: {{output.answer}}")
                .build();

        // When
        List<ChatMessage> result = OnlineScoringEngine.renderMessages(
                List.of(message), null, trace);

        // Then - should render input but leave output variable empty
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOf(UserMessage.class);
        String rendered = ((UserMessage) result.get(0)).singleText();
        assertThat(rendered).contains("Q: What is AI?");
        // output.answer will be empty or null since output doesn't exist
    }

    @Test
    @DisplayName("Auto-extract mode: empty variables map triggers auto-extract")
    void testEmptyVariablesMapTriggersAutoExtract() {
        // Given
        JsonNode input = JsonUtils.getMapper().valueToTree(Map.of("text", "Hello World"));

        Trace trace = Trace.builder()
                .id(UUID.randomUUID())
                .input(input)
                .build();

        LlmAsJudgeMessage message = LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .content("Input: {{input.text}}")
                .build();

        // When - empty map should trigger auto-extract mode
        List<ChatMessage> result = OnlineScoringEngine.renderMessages(
                List.of(message), Map.of(), trace);

        // Then
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).isInstanceOf(UserMessage.class);
        String rendered = ((UserMessage) result.get(0)).singleText();
        assertThat(rendered).isEqualTo("Input: Hello World");
    }

    @Test
    @DisplayName("Auto-extract mode: multiple messages")
    void testAutoExtractMultipleMessages() {
        // Given
        JsonNode input = JsonUtils.getMapper().valueToTree(Map.of("context", "AI context"));
        JsonNode output = JsonUtils.getMapper().valueToTree(Map.of("response", "AI response"));

        Trace trace = Trace.builder()
                .id(UUID.randomUUID())
                .input(input)
                .output(output)
                .build();

        LlmAsJudgeMessage systemMessage = LlmAsJudgeMessage.builder()
                .role(ChatMessageType.SYSTEM)
                .content("You are evaluating: {{input.context}}")
                .build();

        LlmAsJudgeMessage userMessage = LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .content("Does this make sense? {{output.response}}")
                .build();

        // When
        List<ChatMessage> result = OnlineScoringEngine.renderMessages(
                List.of(systemMessage, userMessage), null, trace);

        // Then
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).isInstanceOf(SystemMessage.class);
        assertThat(result.get(1)).isInstanceOf(UserMessage.class);
        assertThat(((SystemMessage) result.get(0)).text()).isEqualTo("You are evaluating: AI context");
        assertThat(((UserMessage) result.get(1)).singleText()).isEqualTo("Does this make sense? AI response");
    }
}
