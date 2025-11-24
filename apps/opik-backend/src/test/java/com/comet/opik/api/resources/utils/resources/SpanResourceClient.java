package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.DeleteFeedbackScore;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreBatchContainer;
import com.comet.opik.api.FeedbackScoreBatchContainer.FeedbackScoreBatch;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.Span;
import com.comet.opik.api.SpanBatch;
import com.comet.opik.api.SpanBatchUpdate;
import com.comet.opik.api.SpanSearchStreamRequest;
import com.comet.opik.api.SpanUpdate;
import com.comet.opik.api.filter.SpanFilter;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.SpanType;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.llm.openai.OpenaiModelName;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.resources.utils.TestUtils.getIdFromLocation;
import static com.comet.opik.api.resources.utils.TestUtils.toURLEncodedQueryParam;
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
            assertThat(response.hasEntity()).isFalse();
            var actualId = getIdFromLocation(response.getLocation());
            if (span.id() != null) {
                assertThat(actualId).isEqualTo(span.id());
            } else {
                assertThat(actualId).isNotNull();
            }
            return actualId;
        }
    }

    public Response createSpan(Span span, String apiKey, String workspaceName, int expectedStatus) {
        var response = callCreateSpan(span, apiKey, workspaceName);

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

            return getIdFromLocation(response.getLocation());
        }
    }

    public Response callCreateSpan(Span span, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(span));
    }

    public void updateSpan(UUID spanId, SpanUpdate spanUpdate, String apiKey, String workspaceName) {
        try (var response = updateSpan(spanId, spanUpdate, apiKey, workspaceName, HttpStatus.SC_NO_CONTENT)) {
            assertThat(response.hasEntity()).isFalse();
        }
    }

    public Response updateSpan(
            UUID spanId, SpanUpdate spanUpdate, String apiKey, String workspaceName, int expectedStatus) {
        var response = callUpdateSpan(spanId, spanUpdate, apiKey, workspaceName);
        assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
        return response;
    }

    public Response callUpdateSpan(UUID spanId, SpanUpdate spanUpdate, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path(spanId.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .method(HttpMethod.PATCH, Entity.json(spanUpdate));
    }

    public void feedbackScores(List<FeedbackScoreBatchItem> score, String apiKey, String workspaceName) {

        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("feedback-scores")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(FeedbackScoreBatch.builder().scores(score).build()))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    public Response callFeedbackScoresWithContainer(FeedbackScoreBatchContainer request, String apiKey,
            String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("feedback-scores")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(request));
    }

    public Response callGetSpansWithQueryParams(String apiKey, String workspaceName, Map<String, String> queryParams) {
        WebTarget target = addQueryParameters(client.target(RESOURCE_PATH.formatted(baseURI)), queryParams);

        return target
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
    }

    public Response callGetSpansWithQueryParamsAndCookie(String sessionToken, String workspaceName,
            Map<String, String> queryParams) {
        WebTarget target = addQueryParameters(client.target(RESOURCE_PATH.formatted(baseURI)), queryParams);

        return target
                .request()
                .cookie(RequestContext.SESSION_COOKIE, sessionToken)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
    }

    public Response callFeedbackScoresWithContainerAndCookie(FeedbackScoreBatchContainer request, String sessionToken,
            String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("feedback-scores")
                .request()
                .cookie(RequestContext.SESSION_COOKIE, sessionToken)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(request));
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

    public void deleteSpanFeedbackScore(DeleteFeedbackScore score, UUID spanId, String apiKey, String workspaceName) {

        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(spanId.toString())
                .path("feedback-scores")
                .path("delete")
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(score))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    public void batchCreateSpans(List<Span> spans, String apiKey, String workspaceName) {
        try (var actualResponse = callBatchCreateSpans(spans, apiKey, workspaceName)) {
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    public Response callBatchCreateSpans(List<Span> spans, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("batch")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(SpanBatch.builder().spans(spans).build()));
    }

    public Span getById(UUID id, String workspaceName, String apiKey) {
        return getById(id, workspaceName, apiKey, false);
    }

    public Span getById(UUID id, String workspaceName, String apiKey, boolean stripAttachments) {
        try (var response = callGetSpanIdApi(id, workspaceName, apiKey, stripAttachments)) {
            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
            assertThat(response.hasEntity()).isTrue();
            return response.readEntity(Span.class);
        }
    }

    public Response callGetSpanIdApi(UUID id, String workspaceName, String apiKey) {
        return callGetSpanIdApi(id, workspaceName, apiKey, false);
    }

    public Response callGetSpanIdApi(UUID id, String workspaceName, String apiKey, boolean stripAttachments) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path(id.toString())
                .queryParam("strip_attachments", stripAttachments)
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
    }

    public Span.SpanPage getByTraceIdAndProject(UUID traceId, String projectName, String workspaceName, String apiKey) {
        return getByTraceIdAndProject(traceId, projectName, workspaceName, apiKey, false, false);
    }

    public Span.SpanPage getByTraceIdAndProject(UUID traceId, String projectName, String workspaceName, String apiKey,
            boolean truncate, boolean stripAttachments) {
        var requestBuilder = client.target(RESOURCE_PATH.formatted(baseURI))
                .queryParam("trace_id", traceId.toString());

        if (StringUtils.isNotEmpty(projectName)) {
            requestBuilder = requestBuilder.queryParam("project_name", projectName);
        }

        if (truncate) {
            requestBuilder = requestBuilder.queryParam("truncate", true);
        }

        if (stripAttachments) {
            requestBuilder = requestBuilder.queryParam("strip_attachments", true);
        }

        var response = requestBuilder
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();

        assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
        return response.readEntity(Span.SpanPage.class);
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
        int completionTokens = randomNumber(1, 500);
        int promptTokens = randomNumber(1, 500);
        return Map.of("completion_tokens", completionTokens,
                "prompt_tokens", promptTokens,
                "total_tokens", completionTokens + promptTokens);
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

    public Span.SpanPage findSpans(String workspaceName, String apiKey, String projectName,
            UUID projectId, Integer page, Integer size, UUID traceId, SpanType type, List<? extends SpanFilter> filters,
            List<SortingField> sortingFields, List<Span.SpanField> exclude) {
        return findSpans(workspaceName, apiKey, projectName, projectId, page, size, traceId, type, filters,
                sortingFields, exclude, null, null);
    }

    public Span.SpanPage findSpans(String workspaceName, String apiKey, String projectName,
            UUID projectId, Integer page, Integer size, UUID traceId, SpanType type, List<? extends SpanFilter> filters,
            List<SortingField> sortingFields, List<Span.SpanField> exclude, String fromTime, String toTime) {
        WebTarget webTarget = client.target(RESOURCE_PATH.formatted(baseURI));

        if (page != null) {
            webTarget = webTarget.queryParam("page", page);
        }

        if (size != null) {
            webTarget = webTarget.queryParam("size", size);
        }

        if (projectName != null) {
            webTarget = webTarget.queryParam("project_name", projectName);
        }

        if (projectId != null) {
            webTarget = webTarget.queryParam("project_id", projectId);
        }

        if (traceId != null) {
            webTarget = webTarget.queryParam("trace_id", traceId);
        }

        if (type != null) {
            webTarget = webTarget.queryParam("type", type);
        }

        if (filters != null) {
            webTarget = webTarget.queryParam("filters", toURLEncodedQueryParam(filters));
        }

        if (sortingFields != null) {
            webTarget = webTarget.queryParam("sorting", toURLEncodedQueryParam(sortingFields));
        }

        if (!CollectionUtils.isEmpty(exclude)) {
            webTarget = webTarget.queryParam("exclude", toURLEncodedQueryParam(exclude));
        }

        if (fromTime != null) {
            webTarget = webTarget.queryParam("from_time", fromTime);
        }

        if (toTime != null) {
            webTarget = webTarget.queryParam("to_time", toTime);
        }

        try (var actualResponse = webTarget
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(actualResponse.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return actualResponse.readEntity(Span.SpanPage.class);
        }
    }

    public ProjectStats getSpansStats(String projectName,
            UUID projectId,
            List<? extends SpanFilter> filters,
            String apiKey,
            String workspaceName,
            Map<String, String> queryParams) {
        return getSpansStats(projectName, projectId, filters, apiKey, workspaceName, queryParams, HttpStatus.SC_OK);
    }

    public ProjectStats getSpansStats(String projectName,
            UUID projectId,
            List<? extends SpanFilter> filters,
            String apiKey,
            String workspaceName,
            Map<String, String> queryParams,
            int expectedStatus) {
        WebTarget webTarget = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("stats");

        if (projectName != null) {
            webTarget = webTarget.queryParam("project_name", projectName);
        }

        if (filters != null) {
            webTarget = webTarget.queryParam("filters", toURLEncodedQueryParam(filters));
        }

        if (projectId != null) {
            webTarget = webTarget.queryParam("project_id", projectId);
        }

        webTarget = queryParams.entrySet()
                .stream()
                .reduce(webTarget, (acc, entry) -> acc.queryParam(entry.getKey(), entry.getValue()), (a, b) -> b);

        var actualResponse = webTarget
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();

        assertThat(actualResponse.getStatus()).isEqualTo(expectedStatus);
        if (expectedStatus == HttpStatus.SC_OK) {
            return actualResponse.readEntity(ProjectStats.class);
        }

        return null;
    }

    public void batchUpdateSpans(SpanBatchUpdate batchUpdate, String apiKey, String workspaceName) {
        try (var actualResponse = callBatchUpdateSpans(batchUpdate, apiKey, workspaceName)) {
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    public Response callBatchUpdateSpans(SpanBatchUpdate batchUpdate, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("batch")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .method(HttpMethod.PATCH, Entity.json(batchUpdate));
    }

}
