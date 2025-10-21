package com.comet.opik.domain.llm;

import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.Content;
import dev.langchain4j.model.openai.internal.chat.ContentType;
import dev.langchain4j.model.openai.internal.chat.ImageUrl;
import dev.langchain4j.model.openai.internal.chat.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
