package com.comet.opik.domain.llm.langchain4j;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for OpikUserMessage serialization to verify the JSON output matches
 * the format expected by custom LLM providers like Qwen3-Omni.
 *
 * Expected format for multimodal messages:
 * {
 *   "role": "user",
 *   "content": [
 *     {"type": "image_url", "image_url": {"url": "https://example.com/image.jpg"}},
 *     {"type": "audio_url", "audio_url": {"url": "https://example.com/audio.wav"}},
 *     {"type": "video_url", "video_url": {"url": "https://example.com/video.mp4"}},
 *     {"type": "text", "text": "What can you see and hear?"}
 *   ]
 * }
 */
class OpikUserMessageSerializationTest {

    private final ObjectMapper objectMapper = JsonUtils.getMapper();

    // Test URLs - using example.com for generic tests
    private static final String EXAMPLE_IMAGE_URL = "https://example.com/image.jpg";
    private static final String EXAMPLE_VIDEO_URL = "https://example.com/video.mp4";
    private static final String EXAMPLE_AUDIO_URL = "https://example.com/audio.wav";

    // Qwen3-Omni demo URLs for multimodal test
    private static final String QWEN_IMAGE_URL = "https://qianwen-res.oss-cn-beijing.aliyuncs.com/Qwen3-Omni/demo/cars.jpg";
    private static final String QWEN_AUDIO_URL = "https://qianwen-res.oss-cn-beijing.aliyuncs.com/Qwen3-Omni/demo/cough.wav";

    @Test
    void shouldSerializeAudioUrlInExpectedFormat() throws Exception {
        // Given - message with audio URL
        var message = OpikUserMessage.builder()
                .addAudioUrl(EXAMPLE_AUDIO_URL)
                .addText("What can you hear in this audio?")
                .build();

        // When
        String json = objectMapper.writeValueAsString(message);
        JsonNode root = objectMapper.readTree(json);

        // Then - verify structure
        assertThat(root.get("role").asText()).isEqualTo("user");
        assertThat(root.get("content").isArray()).isTrue();
        assertThat(root.get("content").size()).isEqualTo(2);

        // Verify audio_url content part
        JsonNode audioContent = root.get("content").get(0);
        assertThat(audioContent.get("type").asText()).isEqualTo("audio_url");
        assertThat(audioContent.get("audio_url").get("url").asText())
                .isEqualTo(EXAMPLE_AUDIO_URL);

        // Verify text content part
        JsonNode textContent = root.get("content").get(1);
        assertThat(textContent.get("type").asText()).isEqualTo("text");
        assertThat(textContent.get("text").asText())
                .isEqualTo("What can you hear in this audio?");
    }

    @Test
    void shouldSerializeVideoUrlInExpectedFormat() throws Exception {
        // Given - message with video URL
        var message = OpikUserMessage.builder()
                .addVideoUrl(EXAMPLE_VIDEO_URL)
                .addText("What's happening in this video?")
                .build();

        // When
        String json = objectMapper.writeValueAsString(message);
        JsonNode root = objectMapper.readTree(json);

        // Then - verify structure
        assertThat(root.get("role").asText()).isEqualTo("user");
        assertThat(root.get("content").isArray()).isTrue();

        // Verify video_url content part
        JsonNode videoContent = root.get("content").get(0);
        assertThat(videoContent.get("type").asText()).isEqualTo("video_url");
        assertThat(videoContent.get("video_url").get("url").asText())
                .isEqualTo(EXAMPLE_VIDEO_URL);
    }

    @Test
    void shouldSerializeImageUrlInExpectedFormat() throws Exception {
        // Given - message with image URL
        var message = OpikUserMessage.builder()
                .addImageUrl(EXAMPLE_IMAGE_URL)
                .addText("What's in this image?")
                .build();

        // When
        String json = objectMapper.writeValueAsString(message);
        JsonNode root = objectMapper.readTree(json);

        // Then - verify structure
        assertThat(root.get("role").asText()).isEqualTo("user");
        assertThat(root.get("content").isArray()).isTrue();

        // Verify image_url content part
        JsonNode imageContent = root.get("content").get(0);
        assertThat(imageContent.get("type").asText()).isEqualTo("image_url");
        assertThat(imageContent.get("image_url").get("url").asText())
                .isEqualTo(EXAMPLE_IMAGE_URL);
    }

    @Test
    void shouldSerializeMultimodalMessageWithImageAudioAndText() throws Exception {
        // Given - multimodal message matching Qwen3-Omni format
        var message = OpikUserMessage.builder()
                .addImageUrl(QWEN_IMAGE_URL)
                .addAudioUrl(QWEN_AUDIO_URL)
                .addText("What can you see and hear? Answer in one sentence.")
                .build();

        // When
        String json = objectMapper.writeValueAsString(message);
        JsonNode root = objectMapper.readTree(json);

        // Then - verify complete structure
        assertThat(root.get("role").asText()).isEqualTo("user");
        assertThat(root.get("content").isArray()).isTrue();
        assertThat(root.get("content").size()).isEqualTo(3);

        // Verify image_url content part
        JsonNode imageContent = root.get("content").get(0);
        assertThat(imageContent.get("type").asText()).isEqualTo("image_url");
        assertThat(imageContent.get("image_url").get("url").asText())
                .isEqualTo(QWEN_IMAGE_URL);

        // Verify audio_url content part
        JsonNode audioContent = root.get("content").get(1);
        assertThat(audioContent.get("type").asText()).isEqualTo("audio_url");
        assertThat(audioContent.get("audio_url").get("url").asText())
                .isEqualTo(QWEN_AUDIO_URL);

        // Verify text content part
        JsonNode textContent = root.get("content").get(2);
        assertThat(textContent.get("type").asText()).isEqualTo("text");
        assertThat(textContent.get("text").asText())
                .isEqualTo("What can you see and hear? Answer in one sentence.");
    }

    @Test
    void shouldSerializeAllMediaTypesTogetherInExpectedFormat() throws Exception {
        // Given - message with all media types: image, video, audio, and text
        var message = OpikUserMessage.builder()
                .addImageUrl(EXAMPLE_IMAGE_URL)
                .addVideoUrl(EXAMPLE_VIDEO_URL)
                .addAudioUrl(EXAMPLE_AUDIO_URL)
                .addText("Describe everything you can see and hear.")
                .build();

        // When
        String json = objectMapper.writeValueAsString(message);
        JsonNode root = objectMapper.readTree(json);

        // Then - verify all 4 content parts
        assertThat(root.get("role").asText()).isEqualTo("user");
        assertThat(root.get("content").size()).isEqualTo(4);

        // Verify each type is present with correct structure
        JsonNode content = root.get("content");

        assertThat(content.get(0).get("type").asText()).isEqualTo("image_url");
        assertThat(content.get(0).get("image_url").get("url").asText())
                .isEqualTo(EXAMPLE_IMAGE_URL);

        assertThat(content.get(1).get("type").asText()).isEqualTo("video_url");
        assertThat(content.get(1).get("video_url").get("url").asText())
                .isEqualTo(EXAMPLE_VIDEO_URL);

        assertThat(content.get(2).get("type").asText()).isEqualTo("audio_url");
        assertThat(content.get(2).get("audio_url").get("url").asText())
                .isEqualTo(EXAMPLE_AUDIO_URL);

        assertThat(content.get(3).get("type").asText()).isEqualTo("text");
        assertThat(content.get(3).get("text").asText())
                .isEqualTo("Describe everything you can see and hear.");
    }

}
