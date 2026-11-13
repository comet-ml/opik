package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.domain.llm.langchain4j.OpikContent;
import com.comet.opik.domain.llm.langchain4j.OpikContentType;
import com.comet.opik.domain.llm.langchain4j.OpikGeminiChatModel;
import com.comet.opik.domain.llm.langchain4j.OpikUserMessage;
import com.comet.opik.domain.llm.langchain4j.VideoUrl;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmTestClients;
import com.comet.opik.infrastructure.llm.RemoteMediaInliner;
import com.comet.opik.infrastructure.llm.VideoMimeTypeUtils;
import com.comet.opik.infrastructure.net.DestinationGuard;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.head;
import static com.github.tomakehurst.wiremock.client.WireMock.headRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Slf4j
@ExtendWith(MockitoExtension.class)
@DisplayName("Gemini Video Support Tests")
class GeminiVideoSupportTest {

    @Mock
    private ChatModel mockDelegate;

    @Mock
    private ChatResponse mockChatResponse;

    @Captor
    private ArgumentCaptor<ChatRequest> chatRequestCaptor;

    /** Media URLs are downloaded and inlined before Gemini sees them, so they are served locally. */
    private static final WireMockUtils.WireMockRuntime WIRE_MOCK = WireMockUtils.startWireMock();
    private static final byte[] VIDEO_BYTES = RandomUtils.secure().randomBytes(64);
    private static final ImageContent INLINED_VIDEO = ImageContent.from(
            Base64.getEncoder().encodeToString(VIDEO_BYTES), "video/mp4");

    private static final RemoteMediaInliner REMOTE_MEDIA_INLINER = LlmTestClients.remoteMediaInliner(
            DestinationGuard.Mode.RELAXED);
    private static final VideoMimeTypeUtils VIDEO_MIME_TYPE_UTILS = LlmTestClients.videoMimeTypeUtils(
            DestinationGuard.Mode.RELAXED);

    @AfterAll
    static void tearDown() {
        WIRE_MOCK.server().stop();
    }

    private static String stubVideo(String fileName) {
        var path = "/%s/%s".formatted(UUID.randomUUID(), fileName);
        WIRE_MOCK.server().stubFor(get(urlPathEqualTo(path))
                .willReturn(aResponse().withHeader("Content-Type", "video/mp4").withBody(VIDEO_BYTES)));
        return WIRE_MOCK.runtimeInfo().getHttpBaseUrl() + path;
    }

    @Nested
    @DisplayName("GeminiLangChainMapper Tests")
    class GeminiLangChainMapperTests {

        @Test
        @DisplayName("Should convert VIDEO_URL to ImageContent for Gemini")
        void shouldConvertVideoUrlToImageContentForGemini() {
            // Given: OpikUserMessage with VIDEO_URL content
            var videoUrl = "https://storage.googleapis.com/example-bucket/video.mp4";
            var videoContent = OpikContent.builder()
                    .type(OpikContentType.VIDEO_URL)
                    .videoUrl(VideoUrl.builder().url(videoUrl).build())
                    .build();

            var opikMessage = OpikUserMessage.builder()
                    .content(List.of(videoContent))
                    .build();

            // When: Convert using GeminiLangChainMapper
            ChatMessage result = GeminiLangChainMapper.INSTANCE.toChatMessageForGemini(opikMessage,
                    LlmTestClients.VIDEO_MIME_TYPE_UTILS);

            // Then: Should be UserMessage with ImageContent (not VideoContent)
            assertThat(result).isInstanceOf(UserMessage.class);
            var userMessage = (UserMessage) result;
            assertThat(userMessage.contents()).hasSize(1);
            assertThat(userMessage.contents().get(0)).isInstanceOf(ImageContent.class);

            var imageContent = (ImageContent) userMessage.contents().get(0);
            assertThat(imageContent.image().url()).hasToString(videoUrl);

            log.info("SUCCESS: VIDEO_URL converted to ImageContent for Gemini");
        }

        @Test
        @DisplayName("an extensionless video URL takes the MIME type its HEAD response reports")
        void extensionlessVideoUrlTakesTheMimeTypeFromHead() {
            var path = "/" + UUID.randomUUID();
            var mimeType = "video/" + RandomStringUtils.secure().nextAlphabetic(10).toLowerCase();
            WIRE_MOCK.server().stubFor(head(urlPathEqualTo(path))
                    .willReturn(aResponse().withHeader("Content-Type", mimeType + "; charset=utf-8")));
            var videoUrl = WIRE_MOCK.runtimeInfo().getHttpBaseUrl() + path;
            var expectedMessage = UserMessage.from(ImageContent.from(Image.builder()
                    .url(URI.create(videoUrl))
                    .mimeType(mimeType)
                    .build()));

            var actualMessage = GeminiLangChainMapper.INSTANCE.toChatMessageForGemini(videoMessage(videoUrl),
                    VIDEO_MIME_TYPE_UTILS);

            assertThat(actualMessage).isEqualTo(expectedMessage);
        }

        @Test
        @DisplayName("an extensionless video URL whose HEAD response redirects gets no MIME type, and the redirect isn't followed")
        void redirectedHeadGivesNoMimeType() {
            var path = "/" + UUID.randomUUID();
            var target = "/" + UUID.randomUUID();
            WIRE_MOCK.server().stubFor(head(urlPathEqualTo(path))
                    .willReturn(aResponse().withStatus(302).withHeader("Location", target)));
            WIRE_MOCK.server().stubFor(head(urlPathEqualTo(target))
                    .willReturn(aResponse().withHeader("Content-Type", "video/mp4")));
            var videoUrl = WIRE_MOCK.runtimeInfo().getHttpBaseUrl() + path;
            var expectedMessage = UserMessage.from(ImageContent.from(Image.builder()
                    .url(URI.create(videoUrl))
                    .build()));

            var actualMessage = GeminiLangChainMapper.INSTANCE.toChatMessageForGemini(videoMessage(videoUrl),
                    VIDEO_MIME_TYPE_UTILS);

            assertThat(actualMessage).isEqualTo(expectedMessage);
            WIRE_MOCK.server().verify(0, headRequestedFor(urlPathEqualTo(target)));
        }

        private static OpikUserMessage videoMessage(String videoUrl) {
            return OpikUserMessage.builder()
                    .content(List.of(OpikContent.builder()
                            .type(OpikContentType.VIDEO_URL)
                            .videoUrl(VideoUrl.builder().url(videoUrl).build())
                            .build()))
                    .build();
        }

        @Test
        @DisplayName("Should convert a base64 data URI VIDEO_URL to inline ImageContent for Gemini")
        void shouldConvertBase64VideoUrlToInlineImageContentForGemini() {
            var base64Data = "AAAAIGZ0eXBpc29tAAACAGlzb21pc28yYXZjMW1wNDEAAAAIZnJlZQAACKBtZGF0";
            var videoContent = OpikContent.builder()
                    .type(OpikContentType.VIDEO_URL)
                    .videoUrl(VideoUrl.builder().url("data:video/mp4;base64," + base64Data).build())
                    .build();
            var opikMessage = OpikUserMessage.builder()
                    .content(List.of(videoContent))
                    .build();

            ChatMessage result = GeminiLangChainMapper.INSTANCE.toChatMessageForGemini(opikMessage,
                    LlmTestClients.VIDEO_MIME_TYPE_UTILS);

            assertThat(result).isEqualTo(UserMessage.from(ImageContent.from(base64Data, "video/mp4")));
        }

        @Test
        @DisplayName("Should convert TEXT content normally for Gemini")
        void shouldConvertTextContentNormallyForGemini() {
            // Given: OpikUserMessage with TEXT content
            var textContent = OpikContent.builder()
                    .type(OpikContentType.TEXT)
                    .text("Describe this video")
                    .build();

            var opikMessage = OpikUserMessage.builder()
                    .content(List.of(textContent))
                    .build();

            // When: Convert using GeminiLangChainMapper
            ChatMessage result = GeminiLangChainMapper.INSTANCE.toChatMessageForGemini(opikMessage,
                    LlmTestClients.VIDEO_MIME_TYPE_UTILS);

            // Then: Should be UserMessage with TextContent
            assertThat(result).isInstanceOf(UserMessage.class);
            var userMessage = (UserMessage) result;
            assertThat(userMessage.contents()).hasSize(1);
            assertThat(userMessage.contents().get(0)).isInstanceOf(TextContent.class);

            var textContentResult = (TextContent) userMessage.contents().get(0);
            assertThat(textContentResult.text()).isEqualTo("Describe this video");

            log.info("SUCCESS: TEXT content converted normally for Gemini");
        }

        @Test
        @DisplayName("Should convert IMAGE_URL content normally for Gemini")
        void shouldConvertImageUrlContentNormallyForGemini() {
            // Given: OpikUserMessage with IMAGE_URL content
            var imageUrl = "https://example.com/image.jpg";
            var imageContent = OpikContent.builder()
                    .type(OpikContentType.IMAGE_URL)
                    .imageUrl(dev.langchain4j.model.openai.internal.chat.ImageUrl.builder()
                            .url(imageUrl)
                            .build())
                    .build();

            var opikMessage = OpikUserMessage.builder()
                    .content(List.of(imageContent))
                    .build();

            // When: Convert using GeminiLangChainMapper
            ChatMessage result = GeminiLangChainMapper.INSTANCE.toChatMessageForGemini(opikMessage,
                    LlmTestClients.VIDEO_MIME_TYPE_UTILS);

            // Then: Should be UserMessage with ImageContent
            assertThat(result).isInstanceOf(UserMessage.class);
            var userMessage = (UserMessage) result;
            assertThat(userMessage.contents()).hasSize(1);
            assertThat(userMessage.contents().get(0)).isInstanceOf(ImageContent.class);

            var imageContentResult = (ImageContent) userMessage.contents().get(0);
            assertThat(imageContentResult.image().url()).hasToString(imageUrl);

            log.info("SUCCESS: IMAGE_URL content converted normally for Gemini");
        }

        @Test
        @DisplayName("Should convert multimodal message with TEXT and VIDEO_URL for Gemini")
        void shouldConvertMultimodalMessageWithTextAndVideoUrlForGemini() {
            // Given: OpikUserMessage with TEXT and VIDEO_URL content
            var textContent = OpikContent.builder()
                    .type(OpikContentType.TEXT)
                    .text("What happens in this video?")
                    .build();

            var videoUrl = "https://storage.googleapis.com/example-bucket/demo.mp4";
            var videoContent = OpikContent.builder()
                    .type(OpikContentType.VIDEO_URL)
                    .videoUrl(VideoUrl.builder().url(videoUrl).build())
                    .build();

            var opikMessage = OpikUserMessage.builder()
                    .content(List.of(textContent, videoContent))
                    .build();

            // When: Convert using GeminiLangChainMapper
            ChatMessage result = GeminiLangChainMapper.INSTANCE.toChatMessageForGemini(opikMessage,
                    LlmTestClients.VIDEO_MIME_TYPE_UTILS);

            // Then: Should be UserMessage with TextContent and ImageContent
            assertThat(result).isInstanceOf(UserMessage.class);
            var userMessage = (UserMessage) result;
            assertThat(userMessage.contents()).hasSize(2);

            assertThat(userMessage.contents().get(0)).isInstanceOf(TextContent.class);
            var textResult = (TextContent) userMessage.contents().get(0);
            assertThat(textResult.text()).isEqualTo("What happens in this video?");

            assertThat(userMessage.contents().get(1)).isInstanceOf(ImageContent.class);
            var imageResult = (ImageContent) userMessage.contents().get(1);
            assertThat(imageResult.image().url()).hasToString(videoUrl);

            log.info("SUCCESS: Multimodal message with TEXT and VIDEO_URL converted for Gemini");
        }

        @Test
        @DisplayName("Should convert ChatCompletionRequest with VIDEO_URL messages for Gemini")
        void shouldConvertChatCompletionRequestForGemini() {
            // Given: ChatCompletionRequest with VIDEO_URL message
            var videoUrl = stubVideo("video.mp4");
            var videoContent = OpikContent.builder()
                    .type(OpikContentType.VIDEO_URL)
                    .videoUrl(VideoUrl.builder().url(videoUrl).build())
                    .build();

            var opikMessage = OpikUserMessage.builder()
                    .content(List.of(videoContent))
                    .build();

            var request = ChatCompletionRequest.builder()
                    .model("gemini-2.0-flash")
                    .messages(List.of(opikMessage))
                    .build();

            // When: Convert using GeminiLangChainMapper
            List<ChatMessage> results = new GeminiClientGenerator(new LlmProviderClientConfig(), REMOTE_MEDIA_INLINER,
                    LlmTestClients.VIDEO_MIME_TYPE_UTILS, LlmTestClients.LLM_HTTP_CLIENT_BUILDERS)
                    .mapMessages(request);

            // Then: Should convert message with VIDEO_URL to ImageContent
            assertThat(results).hasSize(1);
            assertThat(results.get(0)).isInstanceOf(UserMessage.class);

            var userMessage = (UserMessage) results.get(0);
            assertThat(userMessage.contents()).hasSize(1);
            assertThat(userMessage.contents().get(0)).isInstanceOf(ImageContent.class);

            var imageContent = (ImageContent) userMessage.contents().get(0);
            assertThat(imageContent).isEqualTo(INLINED_VIDEO);

            log.info("SUCCESS: ChatCompletionRequest converted for Gemini");
        }
    }

    @Nested
    @DisplayName("OpikGeminiChatModel Tests")
    class OpikGeminiChatModelTests {

        @Test
        @DisplayName("Should convert VideoContent to ImageContent for online scoring")
        void shouldConvertVideoContentToImageContentForOnlineScoring() {
            // Given: OpikGeminiChatModel wrapping mock delegate
            var opikGeminiModel = new OpikGeminiChatModel(mockDelegate, REMOTE_MEDIA_INLINER);

            // Given: UserMessage with VideoContent (from OpikOpenAiChatModel conversion)
            var videoUrl = stubVideo("video.mp4");
            var videoContent = dev.langchain4j.data.message.VideoContent.from(videoUrl);
            var userMessage = UserMessage.from(List.of(videoContent));

            var chatRequest = ChatRequest.builder()
                    .messages(List.of(userMessage))
                    .build();

            // When: Generate response
            when(mockDelegate.chat(any(ChatRequest.class))).thenReturn(mockChatResponse);
            opikGeminiModel.chat(chatRequest);

            // Then: Verify delegate received ImageContent instead of VideoContent
            verify(mockDelegate).chat(chatRequestCaptor.capture());
            var capturedRequest = chatRequestCaptor.getValue();
            var capturedMessages = capturedRequest.messages();

            assertThat(capturedMessages).hasSize(1);
            assertThat(capturedMessages.get(0)).isInstanceOf(UserMessage.class);

            var processedUserMessage = (UserMessage) capturedMessages.get(0);
            assertThat(processedUserMessage.contents()).hasSize(1);
            assertThat(processedUserMessage.contents().get(0)).isInstanceOf(ImageContent.class);

            var imageContent = (ImageContent) processedUserMessage.contents().get(0);
            assertThat(imageContent).isEqualTo(INLINED_VIDEO);

            log.info("SUCCESS: VideoContent converted to ImageContent for online scoring");
        }

        @Test
        @DisplayName("Should preserve TextContent for online scoring")
        void shouldPreserveTextContentForOnlineScoring() {
            // Given: OpikGeminiChatModel wrapping mock delegate
            var opikGeminiModel = new OpikGeminiChatModel(mockDelegate, REMOTE_MEDIA_INLINER);

            // Given: UserMessage with TextContent
            var textContent = TextContent.from("Analyze this content");
            var userMessage = UserMessage.from(List.of(textContent));

            var chatRequest = ChatRequest.builder()
                    .messages(List.of(userMessage))
                    .build();

            // When: Generate response
            when(mockDelegate.chat(any(ChatRequest.class))).thenReturn(mockChatResponse);
            opikGeminiModel.chat(chatRequest);

            // Then: Verify delegate received unchanged TextContent
            verify(mockDelegate).chat(chatRequestCaptor.capture());
            var capturedRequest = chatRequestCaptor.getValue();
            var capturedMessages = capturedRequest.messages();

            assertThat(capturedMessages).hasSize(1);
            assertThat(capturedMessages.get(0)).isInstanceOf(UserMessage.class);

            var processedUserMessage = (UserMessage) capturedMessages.get(0);
            assertThat(processedUserMessage.contents()).hasSize(1);
            assertThat(processedUserMessage.contents().get(0)).isInstanceOf(TextContent.class);

            var processedTextContent = (TextContent) processedUserMessage.contents().get(0);
            assertThat(processedTextContent.text()).isEqualTo("Analyze this content");

            log.info("SUCCESS: TextContent preserved for online scoring");
        }

        @Test
        @DisplayName("Should convert multimodal message with VideoContent and TextContent for online scoring")
        void shouldConvertMultimodalMessageForOnlineScoring() {
            // Given: OpikGeminiChatModel wrapping mock delegate
            var opikGeminiModel = new OpikGeminiChatModel(mockDelegate, REMOTE_MEDIA_INLINER);

            // Given: UserMessage with TextContent and VideoContent
            var textContent = TextContent.from("What happens in this video?");
            var videoUrl = stubVideo("demo.mp4");
            var videoContent = dev.langchain4j.data.message.VideoContent.from(videoUrl);
            var userMessage = UserMessage.from(List.of(textContent, videoContent));

            var chatRequest = ChatRequest.builder()
                    .messages(List.of(userMessage))
                    .build();

            // When: Generate response
            when(mockDelegate.chat(any(ChatRequest.class))).thenReturn(mockChatResponse);
            opikGeminiModel.chat(chatRequest);

            // Then: Verify delegate received TextContent and ImageContent
            verify(mockDelegate).chat(chatRequestCaptor.capture());
            var capturedRequest = chatRequestCaptor.getValue();
            var capturedMessages = capturedRequest.messages();

            assertThat(capturedMessages).hasSize(1);
            assertThat(capturedMessages.get(0)).isInstanceOf(UserMessage.class);

            var processedUserMessage = (UserMessage) capturedMessages.get(0);
            assertThat(processedUserMessage.contents()).hasSize(2);

            assertThat(processedUserMessage.contents().get(0)).isInstanceOf(TextContent.class);
            var processedTextContent = (TextContent) processedUserMessage.contents().get(0);
            assertThat(processedTextContent.text()).isEqualTo("What happens in this video?");

            assertThat(processedUserMessage.contents().get(1)).isInstanceOf(ImageContent.class);
            var processedImageContent = (ImageContent) processedUserMessage.contents().get(1);
            assertThat(processedImageContent).isEqualTo(INLINED_VIDEO);

            log.info("SUCCESS: Multimodal message converted for online scoring");
        }

        @Test
        @DisplayName("Should carry inline base64 video through the conversion instead of throwing")
        void shouldConvertBase64VideoWithoutDereferencingAUrl() {
            var opikGeminiModel = new OpikGeminiChatModel(mockDelegate, REMOTE_MEDIA_INLINER);

            // MinIO-staged attachments arrive as base64 rather than a presigned URL, so url() is null.
            var base64 = "AAAAIGZ0eXBpc29t";
            var userMessage = UserMessage.from(List.of(
                    TextContent.from("What happens in this video?"),
                    dev.langchain4j.data.message.VideoContent.from(base64, "video/mp4")));

            when(mockDelegate.chat(any(ChatRequest.class))).thenReturn(mockChatResponse);
            opikGeminiModel.chat(ChatRequest.builder().messages(List.of(userMessage)).build());

            verify(mockDelegate).chat(chatRequestCaptor.capture());
            var processed = (UserMessage) chatRequestCaptor.getValue().messages().get(0);

            assertThat(processed.contents()).hasSize(2);
            assertThat(processed.contents().get(1)).isInstanceOf(ImageContent.class);
            var image = ((ImageContent) processed.contents().get(1)).image();
            assertThat(image.base64Data()).isEqualTo(base64);
            assertThat(image.mimeType()).isEqualTo("video/mp4");
            assertThat(image.url()).isNull();
        }

        @Test
        @DisplayName("Should keep AudioContent when the same message also carries video")
        void shouldKeepAudioContentAlongsideVideo() {
            var opikGeminiModel = new OpikGeminiChatModel(mockDelegate, REMOTE_MEDIA_INLINER);

            var audioUrl = stubVideo("clip.mp3");
            var videoUrl = stubVideo("demo.mp4");
            var userMessage = UserMessage.from(List.of(
                    AudioContent.from(audioUrl),
                    dev.langchain4j.data.message.VideoContent.from(videoUrl)));

            when(mockDelegate.chat(any(ChatRequest.class))).thenReturn(mockChatResponse);
            opikGeminiModel.chat(ChatRequest.builder().messages(List.of(userMessage)).build());

            verify(mockDelegate).chat(chatRequestCaptor.capture());
            var processedUserMessage = (UserMessage) chatRequestCaptor.getValue().messages().get(0);

            var expectedContents = List.of(
                    AudioContent.from(INLINED_VIDEO.image().base64Data(), INLINED_VIDEO.image().mimeType()),
                    INLINED_VIDEO);
            var actualContents = processedUserMessage.contents();

            assertThat(actualContents).isEqualTo(expectedContents);
        }

        @Test
        @DisplayName("Should pass through non-UserMessage types unchanged")
        void shouldPassThroughNonUserMessagesUnchanged() {
            // Given: OpikGeminiChatModel wrapping mock delegate
            var opikGeminiModel = new OpikGeminiChatModel(mockDelegate, REMOTE_MEDIA_INLINER);

            // Given: Mixed message types (System, User, AI)
            var aiMessage = AiMessage.from("Previous response");
            var userMessage = UserMessage.from("Follow-up question");

            var chatRequest = ChatRequest.builder()
                    .messages(List.of(aiMessage, userMessage))
                    .build();

            // When: Generate response
            when(mockDelegate.chat(any(ChatRequest.class))).thenReturn(mockChatResponse);
            opikGeminiModel.chat(chatRequest);

            // Then: Verify messages passed through unchanged
            verify(mockDelegate).chat(chatRequestCaptor.capture());
            var capturedRequest = chatRequestCaptor.getValue();
            var capturedMessages = capturedRequest.messages();

            assertThat(capturedMessages).hasSize(2);
            assertThat(capturedMessages.get(0)).isInstanceOf(AiMessage.class);
            assertThat(capturedMessages.get(1)).isInstanceOf(UserMessage.class);

            log.info("SUCCESS: Non-UserMessage types passed through unchanged");
        }
    }
}
