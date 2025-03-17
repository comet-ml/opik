package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreBatch;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.Span;
import com.comet.opik.api.SpanBatch;
import com.comet.opik.api.SpanSearchStreamRequest;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.infrastructure.llm.openai.OpenaiModelName;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.core5.http.ContentType;
import org.apache.http.HttpStatus;
import org.glassfish.jersey.client.ChunkedInput;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import uk.co.jemos.podam.api.PodamUtils;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

public class SpanResourceClient extends BaseCommentResourceClient {

    private static final GenericType<ChunkedInput<String>> CHUNKED_INPUT_STRING_GENERIC_TYPE = new GenericType<>() {
    };

    public SpanResourceClient(ClientSupport client, String baseURI) {
        super("%s/v1/private/spans", client, baseURI);
    }

    public UUID createSpan(Span span, String apiKey, String workspaceName) {
        try (var response = createSpan(span, apiKey, workspaceName, HttpStatus.SC_CREATED)) {

            var actualId = TestUtils.getIdFromLocation(response.getLocation());

            if (span.id() != null) {
                assertThat(actualId).isEqualTo(span.id());
            }

            return actualId;
        }
    }

    public Response createSpan(Span span, String apiKey, String workspaceName, int expectedStatus) {
        var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(span));

        assertThat(response.getStatus()).isEqualTo(expectedStatus);

        return response;
    }

    public UUID createSpan(String spanString, String apiKey, String workspaceName, int expectedStatus) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.entity(spanString, ContentType.APPLICATION_JSON.toString()))) {

            assertThat(response.getStatus()).isEqualTo(expectedStatus);

            return TestUtils.getIdFromLocation(response.getLocation());
        }
    }

    public void feedbackScores(List<FeedbackScoreBatchItem> score, String apiKey, String workspaceName) {

        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("feedback-scores")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(new FeedbackScoreBatch(score)))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    public void feedbackScore(UUID entityId, FeedbackScore score, String workspaceName, String apiKey) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(entityId.toString())
                .path("feedback-scores")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(score))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    public void batchCreateSpans(List<Span> spans, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("batch")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(SpanBatch.builder().spans(spans).build()))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    public Span getById(UUID id, String workspaceName, String apiKey) {
        try (var response = callGetSpanIdApi(id, workspaceName, apiKey)) {

            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(Span.class);
        }
    }

    public Response callGetSpanIdApi(UUID id, String workspaceName, String apiKey) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
    }

    public Span.SpanPage getByTraceIdAndProject(UUID traceId, String projectName, String workspaceName, String apiKey) {
        var requestBuilder = client.target(RESOURCE_PATH.formatted(baseURI))
                .queryParam("trace_id", traceId.toString());

        if (StringUtils.isNotEmpty(projectName)) {
            requestBuilder = requestBuilder.queryParam("project", projectName);
        }

        var response = requestBuilder
                .queryParam("project_name", projectName)
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();

        assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
        return response.readEntity(Span.SpanPage.class);
    }

    public void deleteSpan(UUID id, String workspaceName, String apiKey) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .delete()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    public OpenaiModelName randomModel() {
        return OpenaiModelName.values()[randomNumber(0, OpenaiModelName.values().length - 1)];
    }

    public String provider() {
        return "openai";
    }

    private int randomNumber(int min, int max) {
        return PodamUtils.getIntegerInRange(min, max);
    }

    public Map<String, Integer> getTokenUsage() {
        return Map.of("completion_tokens", randomNumber(1, 500), "prompt_tokens", randomNumber(1, 500));
    }

    public List<Span> getStreamAndAssertContent(String apiKey, String workspaceName,
            SpanSearchStreamRequest streamRequest) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("search")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(streamRequest))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

            return getStreamedItems(actualResponse);
        }
    }

    public List<Span> getStreamedItems(Response response) {
        var items = new ArrayList<Span>();
        try (var inputStream = response.readEntity(CHUNKED_INPUT_STRING_GENERIC_TYPE)) {
            String stringItem;
            while ((stringItem = inputStream.read()) != null) {
                items.add(JsonUtils.readValue(stringItem, new TypeReference<>() {
                }));
            }
        }
        return items;
    }

    public <T> T searchSpan(String apiKey, String workspaceName, SpanSearchStreamRequest request, int expectedStatus,
            Class<T> bodyClass) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("search")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
            return actualResponse.readEntity(bodyClass);
        }
    }

    public <T> T findSpans(String apiKey, String workspaceName, String projectName, List<? extends Filter> filters,
            int expectedStatus, Class<T> bodyClass) {
        var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .queryParam("project_name", projectName)
                .queryParam("filters", toURLEncodedQueryParam(filters))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();

        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);

        return actualResponse.readEntity(bodyClass);
    }

    private String toURLEncodedQueryParam(List<? extends Filter> filters) {
        return CollectionUtils.isEmpty(filters)
                ? null
                : URLEncoder.encode(JsonUtils.writeValueAsString(filters), StandardCharsets.UTF_8);
    }
}
