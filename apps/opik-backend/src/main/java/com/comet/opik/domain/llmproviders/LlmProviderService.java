package com.comet.opik.domain.llmproviders;

import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import lombok.NonNull;
import org.glassfish.jersey.server.ChunkedOutput;

public interface LlmProviderService {
    ChatCompletionResponse generate(
            @NonNull ChatCompletionRequest request,
            @NonNull String workspaceId);

    ChunkedOutput<String> generateStream(
            @NonNull ChatCompletionRequest request,
            @NonNull String workspaceId,
            @NonNull LlmProviderStreamHandler streamHandler);
}
