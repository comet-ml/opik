package com.comet.opik.domain.llm;

import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.Content;
import dev.langchain4j.model.openai.internal.chat.ContentType;
import dev.langchain4j.model.openai.internal.chat.ImageUrl;
import dev.langchain4j.model.openai.internal.chat.Message;
import dev.langchain4j.model.openai.internal.chat.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MessageContentNormalizerTest {

    @Test
    void normalizeRequestFlattensImageUrlToPlaceholderForNonVisionModel() {
        ImageUrl imageUrl = ImageUrl.builder()
                .url("https://example.com/image.png")
                .build();

        Content imageContent = Content.builder()
                .type(ContentType.IMAGE_URL)
                .imageUrl(imageUrl)
                .build();

        UserMessage userMessage = UserMessage.builder()
                .content(List.of(imageContent))
                .build();

        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .messages(List.of(userMessage))
                .model("gpt-3.5-turbo")
                .build();

        ChatCompletionRequest normalized = MessageContentNormalizer.normalizeRequest(request);

        assertThat(normalized.messages()).hasSize(1);
        Message normalizedMessage = normalized.messages().get(0);
        assertThat(normalizedMessage).isInstanceOf(UserMessage.class);

        String content = ((UserMessage) normalizedMessage).content().toString();
        assertThat(content).contains("<<<image>>>https://example.com/image.png<<</image>>>");
    }

    @Test
    void normalizeRequestSkipsBlankImageUrls() {
        ImageUrl imageUrl = ImageUrl.builder()
                .url("   ")
                .build();

        Content imageContent = Content.builder()
                .type(ContentType.IMAGE_URL)
                .imageUrl(imageUrl)
                .build();

        UserMessage userMessage = UserMessage.builder()
                .content(List.of(imageContent))
                .build();

        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .messages(List.of(userMessage))
                .model("gpt-3.5-turbo")
                .build();

        ChatCompletionRequest normalized = MessageContentNormalizer.normalizeRequest(request);

        assertThat(normalized.messages()).hasSize(1);
        Message normalizedMessage = normalized.messages().get(0);
        assertThat(normalizedMessage).isInstanceOf(UserMessage.class);

        String content = ((UserMessage) normalizedMessage).content().toString();
        assertThat(content).isEmpty();
    }

    @Test
    void normalizeRequestPreservesStructuredContentForVisionModel() {
        ImageUrl imageUrl = ImageUrl.builder()
                .url("https://example.com/image.png")
                .build();

        Content imageContent = Content.builder()
                .type(ContentType.IMAGE_URL)
                .imageUrl(imageUrl)
                .build();

        UserMessage userMessage = UserMessage.builder()
                .content(List.of(imageContent))
                .build();

        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .messages(List.of(userMessage))
                .model("gpt-4-vision-preview")
                .build();

        ChatCompletionRequest normalized = MessageContentNormalizer.normalizeRequest(request);

        assertThat(normalized).isSameAs(request);
    }
}
