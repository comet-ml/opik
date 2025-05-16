package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.http.HttpStatus;
import org.glassfish.jersey.client.ChunkedInput;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class ChatCompletionsClient {

    private static final String RESOURCE_PATH = "%s/v1/private/chat/completions";

    private static final GenericType<ChunkedInput<String>> CHUNKED_INPUT_STRING_GENERIC_TYPE = new GenericType<>() {
    };

    private static final TypeReference<ChatCompletionResponse> CHAT_COMPLETION_RESPONSE_TYPE_REFERENCE = new TypeReference<>() {
    };

    private static final TypeReference<ErrorMessage> ERROR_MESSAGE_TYPE_REFERENCE = new TypeReference<>() {
    };

    private final ClientSupport clientSupport;
    private final String baseURI;

    public ChatCompletionsClient(ClientSupport clientSupport) {
        this.clientSupport = clientSupport;
        this.baseURI = "http://localhost:%d".formatted(clientSupport.getPort());
    }

    public ChatCompletionResponse create(String apiKey, String workspaceName, ChatCompletionRequest request) {
        assertThat(request.stream()).isFalse();

        try (var response = clientSupport.target(getCreateUrl())
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

            return response.readEntity(ChatCompletionResponse.class);
        }
    }

    public ErrorMessage create(String apiKey, String workspaceName, ChatCompletionRequest request,
            int expectedStatusCode) {
        assertThat(request.stream()).isFalse();

        try (var response = clientSupport.target(getCreateUrl())
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(expectedStatusCode);

            return response.readEntity(ErrorMessage.class);
        }
    }

    public List<ChatCompletionResponse> createAndStream(
            String apiKey, String workspaceName, ChatCompletionRequest request) {
        assertThat(request.stream()).isTrue();

        try (var response = clientSupport.target(getCreateUrl())
                .request()
                .accept(MediaType.SERVER_SENT_EVENTS)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

            return getStreamedEntities(response);
        }
    }

    public ErrorMessage createAndStreamError(
            String apiKey, String workspaceName, ChatCompletionRequest request, int expectedStatusCode) {
        assertThat(request.stream()).isTrue();

        try (var response = clientSupport.target(getCreateUrl())
                .request()
                .accept(MediaType.SERVER_SENT_EVENTS)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(expectedStatusCode);

            return response.readEntity(ErrorMessage.class);
        }
    }

    public List<ErrorMessage> createAndGetStreamedError(
            String apiKey, String workspaceName, ChatCompletionRequest request) {
        assertThat(request.stream()).isTrue();

        try (var response = clientSupport.target(getCreateUrl())
                .request()
                .accept(MediaType.SERVER_SENT_EVENTS)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            return getStreamedError(response);
        }
    }

    private String getCreateUrl() {
        return RESOURCE_PATH.formatted(baseURI);
    }

    private List<ChatCompletionResponse> getStreamedEntities(Response response) {
        var entities = new ArrayList<ChatCompletionResponse>();
        try (var inputStream = response.readEntity(CHUNKED_INPUT_STRING_GENERIC_TYPE)) {
            String chunk;
            while ((chunk = inputStream.read()) != null) {
                entities.add(JsonUtils.readValue(chunk, CHAT_COMPLETION_RESPONSE_TYPE_REFERENCE));
            }
        }
        return entities;
    }

    private List<ErrorMessage> getStreamedError(Response response) {
        var errorMessages = new ArrayList<ErrorMessage>();
        try (var inputStream = response.readEntity(CHUNKED_INPUT_STRING_GENERIC_TYPE)) {
            String chunk;
            while ((chunk = inputStream.read()) != null) {
                errorMessages.add(JsonUtils.readValue(chunk, ERROR_MESSAGE_TYPE_REFERENCE));
            }
        }
        return errorMessages;
    }
}
