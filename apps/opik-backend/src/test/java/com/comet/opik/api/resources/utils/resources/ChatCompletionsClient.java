package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.Builder;
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

    private final ClientSupport clientSupport;
    private final String baseURI;

    public ChatCompletionsClient(ClientSupport clientSupport) {
        this.clientSupport = clientSupport;
        this.baseURI = "http://localhost:%d".formatted(clientSupport.getPort());
    }

    public ChatCompletionResponse get(String apiKey, String workspaceName, ChatCompletionRequest request) {
        assertThat(request.stream()).isFalse();

        try (var response = clientSupport.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NOT_IMPLEMENTED);

            return response.readEntity(ChatCompletionResponse.class);
        }
    }

    public List<ChatCompletionResponse> getStream(String apiKey, String workspaceName, ChatCompletionRequest request) {
        assertThat(request.stream()).isTrue();

        try (var response = clientSupport.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .accept(MediaType.SERVER_SENT_EVENTS)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NOT_IMPLEMENTED);

            return getStreamedItems(response);
        }
    }

    private List<ChatCompletionResponse> getStreamedItems(Response response) {
        var items = new ArrayList<ChatCompletionResponse>();
        try (var inputStream = response.readEntity(CHUNKED_INPUT_STRING_GENERIC_TYPE)) {
            inputStream.setParser(ChunkedInput.createParser("\n"));
            String stringItem;
            while ((stringItem = inputStream.read()) != null) {
                items.add(JsonUtils.readValue(stringItem, CHAT_COMPLETION_RESPONSE_TYPE_REFERENCE));
            }
        }
        return items;
    }
}
