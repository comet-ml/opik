package com.comet.opik.api.resources.v1.priv;

import com.codahale.metrics.annotation.Timed;
import com.comet.opik.api.ChatCompletionRequestWrapper;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.ChunkedOutputHandlers;
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

import java.util.Map;

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
            @RequestBody(content = @Content(schema = @Schema(implementation = ChatCompletionRequestWrapper.class))) @NotNull @Valid ChatCompletionRequestWrapper requestWrapper) {
        var workspaceId = requestContextProvider.get().getWorkspaceId();
        String type;
        Object entity;

        if (StringUtils.isEmpty(requestWrapper.model())) {
            throw new BadRequestException(
                    LlmProviderFactory.ERROR_MODEL_NOT_SUPPORTED.formatted(requestWrapper.model()));
        }

        // Extract extra_body from request
        Map<String, Object> requestExtraBody = requestWrapper.extraBody();

        // Log request parameters from playground
        log.info(
                "Chat completion request - workspaceId: '{}', model: '{}', temperature: '{}', topP: '{}', maxTokens: '{}'",
                workspaceId, requestWrapper.model(), requestWrapper.temperature(), requestWrapper.topP(),
                requestWrapper.maxCompletionTokens());

        if (requestExtraBody != null && !requestExtraBody.isEmpty()) {
            log.info("Playground extra_body parameters: '{}'", requestExtraBody);
        }

        // Convert to ChatCompletionRequest
        var request = requestWrapper.toChatCompletionRequest();

        if (Boolean.TRUE.equals(request.stream())) {
            log.info("Creating and streaming chat completions, workspaceId '{}', model '{}'",
                    workspaceId, request.model());
            type = MediaType.SERVER_SENT_EVENTS;
            var chunkedOutput = new ChunkedOutput<String>(String.class, "\r\n");
            chatCompletionService.createAndStreamResponse(request, workspaceId, requestExtraBody,
                    new ChunkedOutputHandlers(chunkedOutput));
            entity = chunkedOutput;
        } else {
            log.info("Creating chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
            type = MediaType.APPLICATION_JSON;
            entity = chatCompletionService.create(request, workspaceId, requestExtraBody);
        }
        var response = Response.ok().type(type).entity(entity).build();
        log.info("Created chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
        return response;
    }
}
