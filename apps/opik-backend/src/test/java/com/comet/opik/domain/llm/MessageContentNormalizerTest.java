package com.comet.opik.domain.llm;

import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.Content;
import dev.langchain4j.model.openai.internal.chat.ContentType;
import dev.langchain4j.model.openai.internal.chat.ImageUrl;
import dev.langchain4j.model.openai.internal.chat.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MessageContentNormalizerTest {

    @Test
    void normalizeRequestFlattensImageUrlToPlaceholderForNonVisionModel() {
        var imageUrl = ImageUrl.builder()
                .url("https://example.com/image.png")
                .build();

        var imageContent = Content.builder()
                .type(ContentType.IMAGE_URL)
                .imageUrl(imageUrl)
                .build();

        var userMessage = UserMessage.builder()
                .content(List.of(imageContent))
                .build();

        var request = ChatCompletionRequest.builder()
                .messages(List.of(userMessage))
                .model("gpt-3.5-turbo")
                .build();

        var normalized = MessageContentNormalizer.normalizeRequest(request);

        assertThat(normalized.messages()).hasSize(1);
        var normalizedMessage = normalized.messages().getFirst();
        assertThat(normalizedMessage).isInstanceOf(UserMessage.class);

        var content = ((UserMessage) normalizedMessage).content().toString();
        assertThat(content).contains("<<<image>>>https://example.com/image.png<<</image>>>");
    }

    @Test
    void normalizeRequestSkipsBlankImageUrls() {
        var imageUrl = ImageUrl.builder()
                .url("   ")
                .build();

        var imageContent = Content.builder()
                .type(ContentType.IMAGE_URL)
                .imageUrl(imageUrl)
                .build();

        var userMessage = UserMessage.builder()
                .content(List.of(imageContent))
                .build();

        var request = ChatCompletionRequest.builder()
                .messages(List.of(userMessage))
                .model("gpt-3.5-turbo")
                .build();

        var normalized = MessageContentNormalizer.normalizeRequest(request);

        assertThat(normalized.messages()).hasSize(1);
        var normalizedMessage = normalized.messages().getFirst();
        assertThat(normalizedMessage).isInstanceOf(UserMessage.class);

        var content = ((UserMessage) normalizedMessage).content().toString();
        assertThat(content).isEmpty();
    }

    @Test
    void normalizeRequestPreservesStructuredContentForVisionModel() {
        var imageUrl = ImageUrl.builder()
                .url("https://example.com/image.png")
                .build();

        var imageContent = Content.builder()
                .type(ContentType.IMAGE_URL)
                .imageUrl(imageUrl)
                .build();

        var userMessage = UserMessage.builder()
                .content(List.of(imageContent))
                .build();

        var request = ChatCompletionRequest.builder()
                .messages(List.of(userMessage))
                .model("gpt-4-vision-preview")
                .build();

        var normalized = MessageContentNormalizer.normalizeRequest(request);

        assertThat(normalized).isEqualTo(request);
    }

    @Test
    void normalizeRequestExpandsImagePlaceholdersForVisionModel() {
        var userMessage = UserMessage.builder()
                .content("Here is an image:\n<<<image>>>https://example.com/image.png<<</image>>>")
                .build();

        var request = ChatCompletionRequest.builder()
                .messages(List.of(userMessage))
                .model("gpt-4-vision-preview")
                .build();

        var normalized = MessageContentNormalizer.normalizeRequest(request);

        assertThat(normalized.messages()).hasSize(1);
        var normalizedMessage = (UserMessage) normalized.messages().getFirst();

        assertThat(normalizedMessage.content()).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        var contentList = (List<Object>) normalizedMessage.content();

        assertThat(contentList).hasSize(2);

        // First content should be text
        var textContent = (Map<String, Object>) contentList.get(0);
        assertThat(textContent.get("type")).isEqualTo("text");
        assertThat(textContent.get("text")).isEqualTo("Here is an image:\n");

        // Second content should be image
        var imageContent = (Map<String, Object>) contentList.get(1);
        assertThat(imageContent.get("type")).isEqualTo("image_url");
        var imagePayload = (Map<String, Object>) imageContent.get("image_url");
        assertThat(imagePayload.get("url")).isEqualTo("https://example.com/image.png");
    }

    @Test
    void normalizeRequestExpandsBase64ImagePlaceholdersForVisionModel() {
        var base64Image = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==";
        var userMessage = UserMessage.builder()
                .content("Describe this image:\n<<<image>>>" + base64Image + "<<</image>>>")
                .build();

        var request = ChatCompletionRequest.builder()
                .messages(List.of(userMessage))
                .model("gpt-4o")
                .build();

        var normalized = MessageContentNormalizer.normalizeRequest(request);

        assertThat(normalized.messages()).hasSize(1);
        var normalizedMessage = (UserMessage) normalized.messages().getFirst();

        assertThat(normalizedMessage.content()).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        var contentList = (List<Object>) normalizedMessage.content();

        assertThat(contentList).hasSize(2);

        var textContent = (Map<String, Object>) contentList.get(0);
        assertThat(textContent.get("type")).isEqualTo("text");
        assertThat(textContent.get("text")).isEqualTo("Describe this image:\n");

        var imageContent = (Map<String, Object>) contentList.get(1);
        assertThat(imageContent.get("type")).isEqualTo("image_url");
        var imagePayload = (Map<String, Object>) imageContent.get("image_url");
        assertThat(imagePayload.get("url")).isEqualTo(base64Image);
    }

    @Test
    void normalizeRequestExpandsMultipleImagePlaceholdersForVisionModel() {
        var userMessage = UserMessage.builder()
                .content(
                        "First image:\n<<<image>>>https://example.com/image1.png<<</image>>>\n\nSecond image:\n<<<image>>>https://example.com/image2.png<<</image>>>")
                .build();

        var request = ChatCompletionRequest.builder()
                .messages(List.of(userMessage))
                .model("claude-3-5-sonnet-20241022")
                .build();

        var normalized = MessageContentNormalizer.normalizeRequest(request);

        assertThat(normalized.messages()).hasSize(1);
        var normalizedMessage = (UserMessage) normalized.messages().getFirst();

        assertThat(normalizedMessage.content()).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        var contentList = (List<Object>) normalizedMessage.content();

        assertThat(contentList).hasSize(4);

        var firstText = (Map<String, Object>) contentList.get(0);
        assertThat(firstText.get("type")).isEqualTo("text");
        assertThat(firstText.get("text")).isEqualTo("First image:\n");

        var firstImage = (Map<String, Object>) contentList.get(1);
        assertThat(firstImage.get("type")).isEqualTo("image_url");
        assertThat(((Map<String, Object>) firstImage.get("image_url")).get("url"))
                .isEqualTo("https://example.com/image1.png");

        var secondText = (Map<String, Object>) contentList.get(2);
        assertThat(secondText.get("type")).isEqualTo("text");
        assertThat(secondText.get("text")).isEqualTo("\n\nSecond image:\n");

        var secondImage = (Map<String, Object>) contentList.get(3);
        assertThat(secondImage.get("type")).isEqualTo("image_url");
        assertThat(((Map<String, Object>) secondImage.get("image_url")).get("url"))
                .isEqualTo("https://example.com/image2.png");
    }

    @Test
    void normalizeRequestExpandsVideoPlaceholderForVideoModel() {
        var userMessage = UserMessage.builder()
                .content("Here is a clip:\n<<<video>>>https://example.com/video.mp4<<</video>>>")
                .build();

        var request = ChatCompletionRequest.builder()
                .messages(List.of(userMessage))
                .model("qwen/qwen2.5-vl-32b-instruct")
                .build();

        var normalized = MessageContentNormalizer.normalizeRequest(request);

        assertThat(normalized.messages()).hasSize(1);
        var normalizedMessage = (UserMessage) normalized.messages().getFirst();

        assertThat(normalizedMessage.content()).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        var contentList = (List<Object>) normalizedMessage.content();

        assertThat(contentList).hasSize(2);
        var textContent = (Map<String, Object>) contentList.get(0);
        assertThat(textContent.get("type")).isEqualTo("text");
        assertThat(textContent.get("text")).isEqualTo("Here is a clip:\n");

        var videoContent = (Map<String, Object>) contentList.get(1);
        assertThat(videoContent.get("type")).isEqualTo("video_url");
        var videoNode = (Map<String, Object>) videoContent.get("video_url");
        assertThat(videoNode.get("url")).isEqualTo("https://example.com/video.mp4");
    }

    @Test
    void normalizeRequestExpandsImageOnlyMessageForVisionModel() {
        var userMessage = UserMessage.builder()
                .content("<<<image>>>https://example.com/image.png<<</image>>>")
                .build();

        var request = ChatCompletionRequest.builder()
                .messages(List.of(userMessage))
                .model("gemini-1.5-pro")
                .build();

        var normalized = MessageContentNormalizer.normalizeRequest(request);

        assertThat(normalized.messages()).hasSize(1);
        var normalizedMessage = (UserMessage) normalized.messages().getFirst();

        assertThat(normalizedMessage.content()).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        var contentList = (List<Object>) normalizedMessage.content();

        assertThat(contentList).hasSize(1);

        var imageContent = (Map<String, Object>) contentList.get(0);
        assertThat(imageContent.get("type")).isEqualTo("image_url");
        var imageNode = (Map<String, Object>) imageContent.get("image_url");
        assertThat(imageNode.get("url")).isEqualTo("https://example.com/image.png");
    }

    @Test
    void normalizeRequestUnescapesHtmlEntitiesInImageUrls() {
        var userMessage = UserMessage.builder()
                .content("<<<image>>>https://example.com/image.png?param1=value1&amp;param2=value2<<</image>>>")
                .build();

        var request = ChatCompletionRequest.builder()
                .messages(List.of(userMessage))
                .model("gpt-4o")
                .build();

        var normalized = MessageContentNormalizer.normalizeRequest(request);

        assertThat(normalized.messages()).hasSize(1);
        var normalizedMessage = (UserMessage) normalized.messages().getFirst();

        @SuppressWarnings("unchecked")
        var contentList = (List<Object>) normalizedMessage.content();

        assertThat(contentList).hasSize(1);
        var imageContent = (Map<String, Object>) contentList.get(0);

        // HTML entity &amp; should be unescaped to &
        var imagePayload = (Map<String, Object>) imageContent.get("image_url");
        assertThat(imagePayload.get("url")).isEqualTo("https://example.com/image.png?param1=value1&param2=value2");
    }

    @Test
    void normalizeRequestSkipsEmptyImagePlaceholders() {
        var userMessage = UserMessage.builder()
                .content("Text before<<<image>>><<</image>>>Text after")
                .build();

        var request = ChatCompletionRequest.builder()
                .messages(List.of(userMessage))
                .model("gpt-4-vision-preview")
                .build();

        var normalized = MessageContentNormalizer.normalizeRequest(request);

        assertThat(normalized.messages()).hasSize(1);
        var normalizedMessage = (UserMessage) normalized.messages().getFirst();

        @SuppressWarnings("unchecked")
        var contentList = (List<Object>) normalizedMessage.content();

        assertThat(contentList).hasSize(2);
        var firstText = (Map<String, Object>) contentList.get(0);
        assertThat(firstText.get("type")).isEqualTo("text");
        assertThat(firstText.get("text")).isEqualTo("Text before");
        var secondText = (Map<String, Object>) contentList.get(1);
        assertThat(secondText.get("type")).isEqualTo("text");
        assertThat(secondText.get("text")).isEqualTo("Text after");
    }

    @Test
    void normalizeRequestLeavesMessageWithoutImagePlaceholdersUnchangedForVisionModel() {
        var userMessage = UserMessage.builder()
                .content("Just plain text without any images")
                .build();

        var request = ChatCompletionRequest.builder()
                .messages(List.of(userMessage))
                .model("gpt-4-vision-preview")
                .build();

        var normalized = MessageContentNormalizer.normalizeRequest(request);

        assertThat(normalized.messages()).hasSize(1);
        var normalizedMessage = (UserMessage) normalized.messages().getFirst();

        // Content should remain as string (not expanded)
        assertThat(normalizedMessage.content()).isInstanceOf(String.class);
        assertThat((String) normalizedMessage.content()).isEqualTo("Just plain text without any images");
    }

    @Test
    void normalizeRequestPreservesUserMessageName() {
        var userMessage = UserMessage.builder()
                .name("test-user")
                .content("<<<image>>>https://example.com/image.png<<</image>>>")
                .build();

        var request = ChatCompletionRequest.builder()
                .messages(List.of(userMessage))
                .model("gpt-4-vision-preview")
                .build();

        var normalized = MessageContentNormalizer.normalizeRequest(request);

        assertThat(normalized.messages()).hasSize(1);
        var normalizedMessage = (UserMessage) normalized.messages().getFirst();

        assertThat(normalizedMessage.name()).isEqualTo("test-user");
    }
}
