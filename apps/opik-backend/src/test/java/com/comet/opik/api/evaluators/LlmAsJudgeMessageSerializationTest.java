package com.comet.opik.api.evaluators;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.ChatMessageType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test serialization and deserialization of LlmAsJudgeMessage with both string and structured content.
 */
class LlmAsJudgeMessageSerializationTest {

    private final ObjectMapper objectMapper = JsonUtils.getMapper();

    @Test
    void testSerializeDeserialize_stringContent() throws Exception {
        // Given
        var message = LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .content("Hello, this is a test message")
                .build();

        // When - serialize and deserialize
        var json = objectMapper.writeValueAsString(message);
        var deserialized = objectMapper.readValue(json, LlmAsJudgeMessage.class);

        // Then
        assertThat(deserialized.role()).isEqualTo(ChatMessageType.USER);
        assertThat(deserialized.isStringContent()).isTrue();
        assertThat(deserialized.content()).isEqualTo("Hello, this is a test message");
        assertThat(deserialized.contentArray()).isNull();
    }

    @Test
    void testSerializeDeserialize_structuredContent() throws Exception {
        // Given - structured content with text and image
        var textContent = LlmAsJudgeMessageContent.builder()
                .type("text")
                .text("What's in this image?")
                .build();

        var imageContent = LlmAsJudgeMessageContent.builder()
                .type("image_url")
                .imageUrl(LlmAsJudgeMessageContent.ImageUrl.builder()
                        .url("https://example.com/image.jpg")
                        .detail("auto")
                        .build())
                .build();

        var message = LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .contentArray(List.of(textContent, imageContent))
                .build();

        // When - serialize and deserialize
        var json = objectMapper.writeValueAsString(message);
        var deserialized = objectMapper.readValue(json, LlmAsJudgeMessage.class);

        // Then
        assertThat(deserialized.role()).isEqualTo(ChatMessageType.USER);
        assertThat(deserialized.isStructuredContent()).isTrue();
        assertThat(deserialized.content()).isNull();

        var contentList = deserialized.contentArray();
        assertThat(contentList).hasSize(2);

        // Verify text content
        assertThat(contentList.get(0).type()).isEqualTo("text");
        assertThat(contentList.get(0).text()).isEqualTo("What's in this image?");

        // Verify image content
        assertThat(contentList.get(1).type()).isEqualTo("image_url");
        assertThat(contentList.get(1).imageUrl().url()).isEqualTo("https://example.com/image.jpg");
        assertThat(contentList.get(1).imageUrl().detail()).isEqualTo("auto");
    }

    @Test
    void testSerializeDeserialize_structuredContentWithVideo() throws Exception {
        // Given - structured content with text and video
        var textContent = LlmAsJudgeMessageContent.builder()
                .type("text")
                .text("What's happening in this video?")
                .build();

        var videoContent = LlmAsJudgeMessageContent.builder()
                .type("video_url")
                .videoUrl(LlmAsJudgeMessageContent.VideoUrl.builder()
                        .url("https://example.com/video.mp4")
                        .build())
                .build();

        var message = LlmAsJudgeMessage.builder()
                .role(ChatMessageType.USER)
                .contentArray(List.of(textContent, videoContent))
                .build();

        // When - serialize and deserialize
        var json = objectMapper.writeValueAsString(message);
        var deserialized = objectMapper.readValue(json, LlmAsJudgeMessage.class);

        // Then
        assertThat(deserialized.role()).isEqualTo(ChatMessageType.USER);
        assertThat(deserialized.isStructuredContent()).isTrue();
        assertThat(deserialized.content()).isNull();

        var contentList = deserialized.contentArray();
        assertThat(contentList).hasSize(2);

        // Verify text content
        assertThat(contentList.get(0).type()).isEqualTo("text");
        assertThat(contentList.get(0).text()).isEqualTo("What's happening in this video?");

        // Verify video content
        assertThat(contentList.get(1).type()).isEqualTo("video_url");
        assertThat(contentList.get(1).videoUrl().url()).isEqualTo("https://example.com/video.mp4");
    }
}
