package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.domain.TextStreamer;
import com.comet.opik.infrastructure.auth.RequestContext;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import dev.ai4j.openai4j.shared.CompletionTokensDetails;
import dev.ai4j.openai4j.shared.Usage;
import io.dropwizard.jersey.errors.ErrorMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

import java.security.SecureRandom;
import java.util.UUID;

@Path("/v1/private/chat/completions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Chat Completions", description = "Chat Completions related resources")
public class ChatCompletionsResource {

    private final @NonNull Provider<RequestContext> requestContextProvider;
    private final @NonNull TextStreamer textStreamer;
    private final @NonNull SecureRandom secureRandom;

    @POST
    @Produces({MediaType.SERVER_SENT_EVENTS, MediaType.APPLICATION_JSON})
    @Operation(operationId = "getChatCompletions", summary = "Get chat completions", description = "Get chat completions", responses = {
            @ApiResponse(responseCode = "501", description = "Chat completions response", content = {
                    @Content(mediaType = "text/event-stream", array = @ArraySchema(schema = @Schema(type = "object", anyOf = {
                            ChatCompletionResponse.class,
                            ErrorMessage.class}))),
                    @Content(mediaType = "application/json", schema = @Schema(implementation = ChatCompletionResponse.class))}),
    })
    public Response get(
            @RequestBody(content = @Content(schema = @Schema(implementation = ChatCompletionRequest.class))) @NotNull @Valid ChatCompletionRequest request) {
        var workspaceId = requestContextProvider.get().getWorkspaceId();
        String type;
        Object entity;
        if (Boolean.TRUE.equals(request.stream())) {
            log.info("Streaming chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
            type = MediaType.SERVER_SENT_EVENTS;
            var flux = Flux.range(0, 10).map(i -> newResponse(request.model()));
            entity = textStreamer.getOutputStream(flux);
        } else {
            log.info("Getting chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
            type = MediaType.APPLICATION_JSON;
            entity = newResponse(request.model());
        }
        var response = Response.status(Response.Status.NOT_IMPLEMENTED).type(type).entity(entity).build();
        log.info("Returned chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
        return response;
    }

    private ChatCompletionResponse newResponse(String model) {
        return ChatCompletionResponse.builder()
                .id(UUID.randomUUID().toString())
                .created((int) (System.currentTimeMillis() / 1000))
                .model(model)
                .usage(Usage.builder()
                        .totalTokens(secureRandom.nextInt())
                        .promptTokens(secureRandom.nextInt())
                        .completionTokens(secureRandom.nextInt())
                        .completionTokensDetails(CompletionTokensDetails.builder()
                                .reasoningTokens(secureRandom.nextInt())
                                .build())
                        .build())
                .systemFingerprint(UUID.randomUUID().toString())
                .build();
    }
}
