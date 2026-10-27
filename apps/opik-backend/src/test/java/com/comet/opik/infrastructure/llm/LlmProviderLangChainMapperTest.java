package com.comet.opik.infrastructure.llm;

import com.comet.opik.domain.llm.langchain4j.AudioUrl;
import com.comet.opik.domain.llm.langchain4j.OpikContent;
import com.comet.opik.domain.llm.langchain4j.OpikContentType;
import com.comet.opik.domain.llm.langchain4j.OpikUserMessage;
import com.comet.opik.domain.llm.langchain4j.VideoUrl;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import dev.langchain4j.model.openai.internal.chat.ImageUrl;
import io.dropwizard.jersey.errors.ErrorMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import static com.comet.opik.domain.llm.ChatCompletionService.UNEXPECTED_ERROR_CALLING_LLM_PROVIDER;
import static org.assertj.core.api.Assertions.assertThat;

class LlmProviderLangChainMapperTest {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderLangChainMapperTest.class);

    private final LlmProviderLangChainMapper mapper = LlmProviderLangChainMapper.INSTANCE;

    private static Throwable providerErrorWith(String body) {
        return new RuntimeException("dev.langchain4j upstream error: " + body);
    }

    static Stream<Arguments> openAiStringCodeErrors() {
        return Stream.of(
                Arguments.of("rate_limit_exceeded", 429),
                Arguments.of("insufficient_quota", 402),
                Arguments.of("invalid_api_key", 401));
    }

    @ParameterizedTest
    @MethodSource("openAiStringCodeErrors")
    @DisplayName("OpenAI-format string code resolves to its HTTP status")
    void resolvesOpenAiStringCode(String code, int expectedStatus) {
        var body = """
                {"error":{"message":"boom","type":"some_type","code":"%s"}}""".formatted(code);

        Optional<ErrorMessage> result = mapper.getErrorObject(providerErrorWith(body), log);

        assertThat(result).isPresent();
        assertThat(result.get().getCode()).isEqualTo(expectedStatus);
    }

    @Test
    @DisplayName("OpenRouter-format numeric code maps straight to its HTTP status (no 429 -> 500 regression)")
    void resolvesOpenRouterNumericCode() {
        var body = """
                {"error":{"code":429,"message":"Rate limited by upstream"}}""";

        Optional<ErrorMessage> result = mapper.getErrorObject(providerErrorWith(body), log);

        assertThat(result).isPresent();
        assertThat(result.get().getCode()).isEqualTo(429);
    }

    private static Stream<Arguments> documentedErrors() {
        var message = "provider says " + UUID.randomUUID();
        BiFunction<LlmProviderLangChainMapper, Throwable, Optional<ErrorMessage>> openAi = (mapper,
                throwable) -> mapper.getErrorObject(throwable, log);
        BiFunction<LlmProviderLangChainMapper, Throwable, Optional<ErrorMessage>> gemini = (mapper,
                throwable) -> mapper.getGeminiErrorObject(throwable, log);
        BiFunction<LlmProviderLangChainMapper, Throwable, Optional<ErrorMessage>> customLlm = (mapper,
                throwable) -> mapper.getCustomLlmErrorObject(throwable, log);
        BiFunction<LlmProviderLangChainMapper, Throwable, Optional<ErrorMessage>> anthropic = (mapper,
                throwable) -> mapper.getAnthropicErrorObject(throwable, log);
        return Stream.of(
                Arguments.of("OpenAI, documented type", openAi,
                        "{\"error\":{\"message\":\"%s\",\"type\":\"invalid_request_error\",\"code\":\"unsupported_value\"}}"
                                .formatted(message),
                        new ErrorMessage(400, message)),
                Arguments.of("OpenAI, undocumented type and code", openAi,
                        "{\"error\":{\"message\":\"%s\",\"type\":\"gateway_error\",\"code\":\"blocked\"}}"
                                .formatted(message),
                        new ErrorMessage(500, UNEXPECTED_ERROR_CALLING_LLM_PROVIDER)),
                Arguments.of("Gemini, documented status", gemini,
                        "{\"error\":{\"code\":429,\"message\":\"%s\",\"status\":\"RESOURCE_EXHAUSTED\"}}"
                                .formatted(message),
                        new ErrorMessage(429, message, "RESOURCE_EXHAUSTED")),
                Arguments.of("Gemini, undocumented status", gemini,
                        "{\"error\":{\"code\":429,\"message\":\"%s\",\"status\":\"THROTTLED\"}}"
                                .formatted(message),
                        new ErrorMessage(429, UNEXPECTED_ERROR_CALLING_LLM_PROVIDER)),
                Arguments.of("Custom LLM, documented code", customLlm,
                        "{\"error\":{\"message\":\"%s\",\"code\":\"invalid_api_key\"}}".formatted(message),
                        new ErrorMessage(401, message, "invalid_api_key")),
                Arguments.of("Custom LLM, text shape", customLlm,
                        "{\"error\":\"%s\"}".formatted(message),
                        new ErrorMessage(400, UNEXPECTED_ERROR_CALLING_LLM_PROVIDER)),
                Arguments.of("Anthropic, documented type", anthropic,
                        "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"%s\"},\"request_id\":\"req_1\"}"
                                .formatted(message),
                        new ErrorMessage(500, message)),
                Arguments.of("Anthropic, undocumented type", anthropic,
                        "{\"type\":\"error\",\"error\":{\"type\":\"proxy_error\",\"message\":\"%s\"}}"
                                .formatted(message),
                        new ErrorMessage(500, UNEXPECTED_ERROR_CALLING_LLM_PROVIDER)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("documentedErrors")
    @DisplayName("a parsed provider error keeps its text only when the provider documents its type, code or status")
    void keepsTheTextOfDocumentedErrorsOnly(String shape,
            BiFunction<LlmProviderLangChainMapper, Throwable, Optional<ErrorMessage>> parse, String body,
            ErrorMessage expectedError) {
        var actualError = parse.apply(mapper, providerErrorWith(body));

        assertThat(actualError).contains(expectedError);
    }

    @Test
    @DisplayName("a throwable without a JSON payload yields empty (caller maps this to 500)")
    void returnsEmptyWhenNoJsonPayload() {
        assertThat(mapper.getErrorObject(new RuntimeException("connection reset"), log)).isEmpty();
    }

    @Test
    @DisplayName("base64 data URI image, video and audio become inline data, so Vertex AI does not guess MIME types from URLs")
    void mapsBase64DataUriMediaToInlineData() {
        var base64Data = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
        var message = OpikUserMessage.builder()
                .content(List.of(
                        OpikContent.builder()
                                .type(OpikContentType.TEXT)
                                .text("Describe these")
                                .build(),
                        OpikContent.builder()
                                .type(OpikContentType.IMAGE_URL)
                                .imageUrl(ImageUrl.builder().url("data:image/jpeg;base64," + base64Data).build())
                                .build(),
                        OpikContent.builder()
                                .type(OpikContentType.VIDEO_URL)
                                .videoUrl(VideoUrl.builder().url("data:video/mp4;base64," + base64Data).build())
                                .build(),
                        OpikContent.builder()
                                .type(OpikContentType.AUDIO_URL)
                                .audioUrl(AudioUrl.builder().url("data:audio/wav;base64," + base64Data).build())
                                .build()))
                .build();

        assertThat(mapper.toChatMessage(message)).isEqualTo(UserMessage.from(
                TextContent.from("Describe these"),
                ImageContent.from(base64Data, "image/jpeg"),
                VideoContent.from(base64Data, "video/mp4"),
                AudioContent.from(base64Data, "audio/wav")));
    }
}
