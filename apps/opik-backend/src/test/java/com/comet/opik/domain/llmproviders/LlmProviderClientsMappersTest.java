package com.comet.opik.domain.llmproviders;

import com.comet.opik.podam.PodamFactoryUtils;
import dev.ai4j.openai4j.chat.AssistantMessage;
import dev.ai4j.openai4j.chat.ChatCompletionChoice;
import dev.ai4j.openai4j.shared.Usage;
import dev.langchain4j.model.anthropic.internal.api.AnthropicContent;
import dev.langchain4j.model.anthropic.internal.api.AnthropicCreateMessageResponse;
import dev.langchain4j.model.anthropic.internal.api.AnthropicUsage;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class LlmProviderClientsMappersTest {
    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class AnthropicMappers {
        // anthropic POJOs don't have setters or builders, therefore podam can't manufacture objects correctly

        @Test
        void testToResponse() {
            var content = new AnthropicContent();
            content.name = podamFactory.manufacturePojo(String.class);
            content.text = podamFactory.manufacturePojo(String.class);
            content.id = podamFactory.manufacturePojo(String.class);

            var usage = new AnthropicUsage();
            usage.inputTokens = podamFactory.manufacturePojo(Integer.class);
            usage.outputTokens = podamFactory.manufacturePojo(Integer.class);

            var response = new AnthropicCreateMessageResponse();
            response.id = podamFactory.manufacturePojo(String.class);
            response.model = podamFactory.manufacturePojo(String.class);
            response.stopReason = podamFactory.manufacturePojo(String.class);
            response.content = List.of(content);
            response.usage = usage;

            var actual = AnthropicToChatCompletionsMapper.INSTANCE.toResponse(response);
            assertThat(actual).isNotNull();
            assertThat(actual.id()).isEqualTo(response.id);
            assertThat(actual.choices()).isEqualTo(List.of(ChatCompletionChoice.builder()
                    .message(AssistantMessage.builder()
                            .name(content.name)
                            .content(content.text)
                            .build())
                    .finishReason(response.stopReason)
                    .build()));
            assertThat(actual.usage()).isEqualTo(Usage.builder()
                    .promptTokens(usage.inputTokens)
                    .completionTokens(usage.outputTokens)
                    .totalTokens(usage.inputTokens + usage.outputTokens)
                    .build());
        }
    }
}
