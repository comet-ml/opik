package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.ChunkedOutputHandlers;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
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
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.glassfish.jersey.server.ChunkedOutput;

import static com.comet.opik.infrastructure.http.CorsFactory.OPIK_ACTUAL_MODEL_HEADER;
import static com.comet.opik.infrastructure.http.CorsFactory.OPIK_PROVIDER_HEADER;

@Path("/v1/private/chat/completions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Chat Completions", description = "Chat Completions related resources")
public class ChatCompletionsResource {

    private final @NonNull Provider<RequestContext> requestContextProvider;
    private final @NonNull ChatCompletionService chatCompletionService;
    private final @NonNull LlmProviderFactory llmProviderFactory;

    @POST
    @Produces({MediaType.SERVER_SENT_EVENTS, MediaType.APPLICATION_JSON})
    @Operation(operationId = "createChatCompletions", summary = "Create chat completions", description = "Create chat completions", responses = {
            @ApiResponse(responseCode = "200", description = "Chat completions response", content = {
                    @Content(mediaType = "text/event-stream", array = @ArraySchema(schema = @Schema(type = "object", anyOf = {
                            ChatCompletionResponse.class,
                            ErrorMessage.class}))),
                    @Content(mediaType = "application/json", schema = @Schema(implementation = ChatCompletionResponse.class))}),
    })
    public Response create(
            @RequestBody(content = @Content(schema = @Schema(implementation = ChatCompletionRequest.class))) @NotNull @Valid ChatCompletionRequest request) {
        var workspaceId = requestContextProvider.get().getWorkspaceId();
        String type;
        Object entity;

        if (StringUtils.isEmpty(request.model())) {
            throw new BadRequestException(LlmProviderFactory.ERROR_MODEL_NOT_SUPPORTED.formatted(request.model()));
        }

        // Get the resolved model info for span tracking
        var resolvedModelInfo = llmProviderFactory.getResolvedModelInfo(request.model());

        if (Boolean.TRUE.equals(request.stream())) {
            log.info("Creating and streaming chat completions, workspaceId '{}', model '{}', actualModel '{}'",
                    workspaceId, request.model(), resolvedModelInfo.actualModel());
            type = MediaType.SERVER_SENT_EVENTS;
            // Use "\n\n" separator for SSE format (each event ends with double newline)
            var chunkedOutput = new ChunkedOutput<String>(String.class, "\n\n");
            chatCompletionService.createAndStreamResponse(request, workspaceId,
                    new ChunkedOutputHandlers(chunkedOutput));
            entity = chunkedOutput;
        } else {
            log.info("Creating chat completions, workspaceId '{}', model '{}', actualModel '{}'",
                    workspaceId, request.model(), resolvedModelInfo.actualModel());
            type = MediaType.APPLICATION_JSON;
            entity = chatCompletionService.create(request, workspaceId);
        }

        // Include actual model and provider in response headers for frontend span tracking.
        // For OPIK_FREE provider, the user-facing model name (e.g., "opik-free-model") differs
        // from the actual model used (e.g., "gpt-4o-mini"), and these headers allow correct cost calculation.
        var response = Response.ok()
                .type(type)
                .entity(entity)
                .header(OPIK_ACTUAL_MODEL_HEADER, resolvedModelInfo.actualModel())
                .header(OPIK_PROVIDER_HEADER, resolvedModelInfo.provider())
                .build();
        log.info("Created chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
        return response;
    }
}
