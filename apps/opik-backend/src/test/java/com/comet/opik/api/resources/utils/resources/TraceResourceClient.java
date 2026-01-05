package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.BatchDeleteByProject;
import com.comet.opik.api.DeleteFeedbackScore;
import com.comet.opik.api.DeleteThreadFeedbackScores;
import com.comet.opik.api.DeleteTraceThreads;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreNames;
import com.comet.opik.api.Project;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceBatch;
import com.comet.opik.api.TraceBatchUpdate;
import com.comet.opik.api.TraceSearchStreamRequest;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.TraceThreadBatchIdentifier;
import com.comet.opik.api.TraceThreadBatchUpdate;
import com.comet.opik.api.TraceThreadIdentifier;
import com.comet.opik.api.TraceThreadSearchStreamRequest;
import com.comet.opik.api.TraceThreadUpdate;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.filter.TraceThreadFilter;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.infrastructure.auth.RequestContext;
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
import org.apache.http.HttpStatus;
import org.glassfish.jersey.client.ChunkedInput;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.comet.opik.api.FeedbackScoreBatchContainer.FeedbackScoreBatch;
import static com.comet.opik.api.FeedbackScoreBatchContainer.FeedbackScoreBatchThread;
import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItemThread;
import static com.comet.opik.api.TraceThread.TraceThreadPage;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

public class TraceResourceClient extends BaseCommentResourceClient {

    private static final GenericType<ChunkedInput<String>> CHUNKED_INPUT_STRING_GENERIC_TYPE = new GenericType<>() {
    };

    public TraceResourceClient(ClientSupport client, String baseURI) {
        super("%s/v1/private/traces", client, baseURI);
    }

    public UUID createTrace(Trace trace, String apiKey, String workspaceName) {
        try (var response = callCreateTrace(trace, apiKey, workspaceName)) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
            UUID actualId = TestUtils.getIdFromLocation(response.getLocation());

            if (trace.id() != null) {
                assertThat(actualId).isEqualTo(trace.id());
            }

            return actualId;
        }
    }

    public Response callCreateTrace(Trace trace, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(trace));
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

    public void deleteTraceFeedbackScore(DeleteFeedbackScore score, UUID traceId, String apiKey, String workspaceName) {

        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(traceId.toString())
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

    public Response callFeedbackScores(List<FeedbackScoreBatchItem> score, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("feedback-scores")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(FeedbackScoreBatch.builder().scores(score).build()));
    }

    public Response callFeedbackScoresWithCookie(List<FeedbackScoreBatchItem> score, String sessionToken,
            String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("feedback-scores")
                .request()
                .cookie(RequestContext.SESSION_COOKIE, sessionToken)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(FeedbackScoreBatch.builder().scores(score).build()));
    }

    public void threadFeedbackScores(List<FeedbackScoreBatchItemThread> score, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("threads")
                .path("feedback-scores")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(FeedbackScoreBatchThread.builder()
                        .scores(score)
                        .build()))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    public Response callThreadFeedbackScores(List<FeedbackScoreBatchItemThread> score, String apiKey,
            String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("threads")
                .path("feedback-scores")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(FeedbackScoreBatchThread.builder()
                        .scores(score)
                        .build()));
    }

    public void feedbackScore(UUID entityId, FeedbackScore score, String workspaceName, String apiKey) {
        try (var actualResponse = callFeedbackScore(entityId, score, workspaceName, apiKey)) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    public Response callFeedbackScore(UUID entityId, FeedbackScore score, String workspaceName, String apiKey) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path(entityId.toString())
                .path("feedback-scores")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(score));
    }

    public void batchCreateTraces(List<Trace> traces, String apiKey, String workspaceName) {
        try (var actualResponse = callBatchCreateTraces(traces, apiKey, workspaceName)) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    public Response callBatchCreateTraces(List<Trace> traces, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("batch")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(TraceBatch.builder().traces(traces).build()));
    }

    public Trace getById(UUID id, String workspaceName, String apiKey) {
        return getById(id, workspaceName, apiKey, false);
    }

    public Trace getById(UUID id, String workspaceName, String apiKey, boolean stripAttachments) {
        var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(id.toString())
                .queryParam("strip_attachments", stripAttachments)
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();

        assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
        return response.readEntity(Trace.class);
    }

    public void deleteTrace(UUID id, String workspaceName, String apiKey) {
        try (var actualResponse = callDeleteTrace(id, apiKey, workspaceName)) {
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    public void deleteTraces(BatchDeleteByProject request, String workspaceName, String apiKey) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("delete")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    public void updateTrace(UUID id, TraceUpdate traceUpdate, String apiKey, String workspaceName) {
        try (var actualResponse = updateTrace(id, traceUpdate, apiKey, workspaceName, HttpStatus.SC_NO_CONTENT)) {
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    public Response updateTrace(
            UUID id, TraceUpdate traceUpdate, String apiKey, String workspaceName, int expectedStatus) {
        var actualResponse = callUpdateTrace(id, traceUpdate, apiKey, workspaceName);
        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
        return actualResponse;
    }

    public Response callUpdateTrace(UUID id, TraceUpdate traceUpdate, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .method(HttpMethod.PATCH, Entity.json(traceUpdate));
    }

    public List<List<FeedbackScoreBatchItem>> createMultiValueScores(List<String> multipleValuesFeedbackScores,
            Project project, String apiKey, String workspaceName) {
        return IntStream.range(0, multipleValuesFeedbackScores.size())
                .mapToObj(i -> {

                    Trace trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                            .name(project.name())
                            .build();

                    createTrace(trace, apiKey, workspaceName);

                    List<FeedbackScoreBatchItem> scores = multipleValuesFeedbackScores.stream()
                            .map(name -> podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                                    .name(name)
                                    .projectName(project.name())
                                    .id(trace.id())
                                    .build())
                            .collect(Collectors.toList());

                    feedbackScores(scores, apiKey, workspaceName);

                    return scores;
                }).toList();
    }

    public List<Trace> getByProjectName(String projectName, String apiKey, String workspace) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .queryParam("project_name", projectName)
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspace)
                .get()) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            assertThat(response.hasEntity()).isTrue();

            return response.readEntity(Trace.TracePage.class).content();
        }
    }

    public void deleteTraceThreads(List<String> threadId, String projectName, UUID projectId, String apiKey,
            String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("threads")
                .path("delete")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(DeleteTraceThreads.builder().threadIds(threadId).projectName(projectName)
                        .projectId(projectId).build()))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    public void deleteThreadFeedbackScores(String projectName,
            String threadId,
            Set<String> scoreNames,
            String author,
            String apiKey,
            String workspaceName) {
        try (var response = callDeleteThreadFeedbackScores(
                projectName, threadId, scoreNames, author, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    public Response callDeleteThreadFeedbackScores(String projectName,
            String threadId,
            Set<String> scoreNames,
            String author,
            String apiKey,
            String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("threads")
                .path("feedback-scores")
                .path("delete")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(DeleteThreadFeedbackScores.builder()
                        .projectName(projectName)
                        .threadId(threadId)
                        .names(scoreNames)
                        .author(author)
                        .build()));
    }

    public TraceThreadPage getTraceThreads(UUID projectId, String projectName, String apiKey, String workspaceName,
            List<TraceThreadFilter> filters, List<SortingField> sortingFields, Map<String, String> queryParams) {

        WebTarget target = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("threads");

        target = Optional.ofNullable(queryParams)
                .orElseGet(Map::of)
                .entrySet()
                .stream()
                .reduce(target, (acc, entry) -> acc.queryParam(entry.getKey(), entry.getValue()), (a, b) -> b);

        if (projectId != null) {
            target = target.queryParam("project_id", projectId);
        }

        if (projectName != null) {
            target = target.queryParam("project_name", projectName);
        }

        if (CollectionUtils.isNotEmpty(filters)) {
            target = target.queryParam("filters", TestUtils.toURLEncodedQueryParam(filters));
        }

        if (CollectionUtils.isNotEmpty(sortingFields)) {
            target = target.queryParam("sorting",
                    URLEncoder.encode(JsonUtils.writeValueAsString(sortingFields), StandardCharsets.UTF_8));
        }

        try (var response = target
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            assertThat(response.hasEntity()).isTrue();
            return response.readEntity(TraceThreadPage.class);
        }
    }

    public Response getTraceThreads(String projectName, String apiKey, String workspaceName,
            List<TraceThreadFilter> filters) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("threads")
                .queryParam("project_name", projectName)
                .queryParam("filters", TestUtils.toURLEncodedQueryParam(filters))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
    }

    public TraceThread getTraceThread(String threadId, UUID projectId, String apiKey, String workspaceName) {
        return getTraceThread(threadId, projectId, false, apiKey, workspaceName);
    }

    public TraceThread getTraceThread(String threadId, UUID projectId, boolean truncate, String apiKey,
            String workspaceName) {

        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("threads")
                .path("retrieve")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(TraceThreadIdentifier.builder()
                        .projectId(projectId)
                        .threadId(threadId)
                        .truncate(truncate)
                        .build()))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            assertThat(response.hasEntity()).isTrue();

            return response.readEntity(TraceThread.class);
        }
    }

    public Response getTraceThreadResponse(String threadId, UUID projectId, String apiKey, String workspace) {
        return callRetrieveThreads(threadId, projectId, apiKey, workspace);
    }

    private Response callRetrieveThreads(String threadId, UUID projectId, String apiKey, String workspace) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("threads")
                .path("retrieve")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspace)
                .post(Entity
                        .json(TraceThreadIdentifier.builder().threadId(threadId).projectId(projectId).build()));
    }

    public List<Trace> getStreamAndAssertContent(String apiKey, String workspaceName,
            TraceSearchStreamRequest streamRequest) {
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

    public List<Trace> getStreamedItems(Response response) {
        var items = new ArrayList<Trace>();
        try (var inputStream = response.readEntity(CHUNKED_INPUT_STRING_GENERIC_TYPE)) {
            String stringItem;
            while ((stringItem = inputStream.read()) != null) {
                items.add(JsonUtils.readValue(stringItem, new TypeReference<>() {
                }));
            }
        }
        return items;
    }

    public ProjectStats getTraceStats(String projectName, UUID projectId, String apiKey, String workspaceName,
            List<? extends TraceFilter> filters, Map<String, String> queryParams) {
        WebTarget webTarget = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("stats");

        if (projectName != null) {
            webTarget = webTarget.queryParam("project_name", projectName);
        }

        if (filters != null) {
            webTarget = webTarget.queryParam("filters", TestUtils.toURLEncodedQueryParam(filters));
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

        assertThat(actualResponse.getStatus()).isEqualTo(HttpStatus.SC_OK);
        return actualResponse.readEntity(ProjectStats.class);
    }

    public ProjectStats getTraceThreadStats(String projectName, UUID projectId, String apiKey, String workspaceName,
            List<? extends TraceThreadFilter> filters, Map<String, String> queryParams) {
        WebTarget webTarget = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("threads")
                .path("stats");

        if (projectName != null) {
            webTarget = webTarget.queryParam("project_name", projectName);
        }

        if (filters != null) {
            webTarget = webTarget.queryParam("filters", TestUtils.toURLEncodedQueryParam(filters));
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

        assertThat(actualResponse.getStatus()).isEqualTo(HttpStatus.SC_OK);
        return actualResponse.readEntity(ProjectStats.class);
    }

    public Trace.TracePage getTraces(String projectName, UUID projectId, String apiKey, String workspaceName,
            List<? extends TraceFilter> filters, List<SortingField> sortingFields, int size,
            Map<String, String> queryParams) {

        int page = Integer.parseInt(queryParams.getOrDefault("page", "1"));

        WebTarget target = client.target(RESOURCE_PATH.formatted(baseURI));

        if (CollectionUtils.isNotEmpty(sortingFields)) {
            target = target.queryParam("sorting",
                    URLEncoder.encode(JsonUtils.writeValueAsString(sortingFields), StandardCharsets.UTF_8));
        }

        if (page > 0) {
            target = target.queryParam("page", page);
        }

        if (size > 0) {
            target = target.queryParam("size", size);
        }

        if (projectName != null) {
            target = target.queryParam("project_name", projectName);
        }

        if (projectId != null) {
            target = target.queryParam("project_id", projectId);
        }

        // Add remaining queryParams (like from_time, to_time)
        WebTarget finalTarget = target;
        target = queryParams.entrySet()
                .stream()
                .filter(e -> !e.getKey().equals("page")) // Skip page as it's already handled
                .reduce(finalTarget, (acc, entry) -> acc.queryParam(entry.getKey(), entry.getValue()), (a, b) -> b);

        var actualResponse = target
                .queryParam("filters", TestUtils.toURLEncodedQueryParam(filters))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();

        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

        return actualResponse.readEntity(Trace.TracePage.class);
    }

    public void openTraceThread(String threadId, UUID projectId, String projectName, String apiKey,
            String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("threads")
                .path("open")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(TraceThreadIdentifier.builder().projectId(projectId).projectName(projectName)
                        .threadId(threadId).build()))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    public void closeTraceThread(String threadId, UUID projectId, String projectName, String apiKey,
            String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("threads")
                .path("close")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(TraceThreadIdentifier.builder().projectId(projectId).projectName(projectName)
                        .threadId(threadId).build()))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    public void closeTraceThreads(Set<String> threadIds, UUID projectId, String projectName, String apiKey,
            String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("threads")
                .path("close")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(TraceThreadBatchIdentifier.builder().projectId(projectId).projectName(projectName)
                        .threadIds(threadIds).build()))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    public List<TraceThread> searchTraceThreadsStream(String projectName, UUID projectId, String apiKey,
            String workspaceName, List<TraceThreadFilter> filters) {
        try (var actualResponse = callSearchTraceThreadStream(projectName, projectId, apiKey, workspaceName, filters)) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

            return getStreamedTraceThreads(actualResponse);
        }
    }

    public Response callSearchTraceThreadStream(String projectName, UUID projectId, String apiKey, String workspaceName,
            List<TraceThreadFilter> filters) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("threads")
                .path("search")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(TraceThreadSearchStreamRequest.builder()
                        .filters(filters)
                        .projectName(projectName)
                        .projectId(projectId)
                        .build()));
    }

    private List<TraceThread> getStreamedTraceThreads(Response response) {
        var items = new ArrayList<TraceThread>();
        try (var inputStream = response.readEntity(CHUNKED_INPUT_STRING_GENERIC_TYPE)) {
            String stringItem;
            while ((stringItem = inputStream.read()) != null) {
                items.add(JsonUtils.readValue(stringItem, new TypeReference<>() {
                }));
            }
        }
        return items;
    }

    public FeedbackScoreNames getTraceThreadsFeedbackScoreNames(UUID projectId, String apiKey, String workspaceName) {

        WebTarget webTarget = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("threads")
                .path("feedback-scores")
                .path("names");

        webTarget = webTarget.queryParam("project_id", projectId);

        try (var actualResponse = webTarget
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            // then
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
            return actualResponse.readEntity(FeedbackScoreNames.class);
        }
    }

    public void updateThread(TraceThreadUpdate threadUpdate, UUID threadModelId, String apiKey, String workspaceName,
            int expectedStatus) {

        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("threads")
                .path(threadModelId.toString())
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .method(HttpMethod.PATCH, Entity.json(threadUpdate))) {

            assertThat(response.getStatus()).isEqualTo(expectedStatus);
        }
    }

    public Response callBatchCreateTracesWithCookie(List<Trace> traces, String sessionToken, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("batch")
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .cookie(RequestContext.SESSION_COOKIE, sessionToken)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(new TraceBatch(traces)));
    }

    public void batchUpdateTraces(TraceBatchUpdate batchUpdate, String apiKey, String workspaceName) {
        try (var actualResponse = callBatchUpdateTraces(batchUpdate, apiKey, workspaceName)) {
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    public Response callBatchUpdateTraces(TraceBatchUpdate batchUpdate, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("batch")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .method(HttpMethod.PATCH, Entity.json(batchUpdate));
    }

    public void batchUpdateThreads(TraceThreadBatchUpdate batchUpdate, String apiKey, String workspaceName) {
        try (var actualResponse = callBatchUpdateThreads(batchUpdate, apiKey, workspaceName)) {
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    public Response callBatchUpdateThreads(TraceThreadBatchUpdate batchUpdate, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("threads")
                .path("batch")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .method(HttpMethod.PATCH, Entity.json(batchUpdate));
    }

    public Response callPostWithCookie(Object body, String sessionToken, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .cookie(RequestContext.SESSION_COOKIE, sessionToken)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(body));
    }

    public Response callPostToPathWithCookie(String pathSuffix, Object body, String sessionToken,
            String workspaceName) {
        WebTarget target = addPathSegments(client.target(RESOURCE_PATH.formatted(baseURI)), pathSuffix);

        return target
                .request()
                .cookie(RequestContext.SESSION_COOKIE, sessionToken)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(body));
    }

    public Response callPutToPathWithCookie(String pathSuffix, Object body, String sessionToken, String workspaceName) {
        WebTarget target = addPathSegments(client.target(RESOURCE_PATH.formatted(baseURI)), pathSuffix);

        return target
                .request()
                .cookie(RequestContext.SESSION_COOKIE, sessionToken)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(body));
    }

    public Response callGetWithQueryParamAndCookie(String queryParamKey, String queryParamValue, String sessionToken,
            String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .queryParam(queryParamKey, queryParamValue)
                .request()
                .cookie(RequestContext.SESSION_COOKIE, sessionToken)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
    }

    public Response callGetWithPathAndCookie(String pathSuffix, String queryParamKey, String queryParamValue,
            String sessionToken, String workspaceName) {
        WebTarget target = addPathSegments(client.target(RESOURCE_PATH.formatted(baseURI)), pathSuffix);

        // Add query parameter if provided
        if (queryParamKey != null && queryParamValue != null) {
            target = target.queryParam(queryParamKey, queryParamValue);
        }

        return target
                .request()
                .cookie(RequestContext.SESSION_COOKIE, sessionToken)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
    }

    public Response callSearchTracesStreamWithCookie(TraceSearchStreamRequest streamRequest, String sessionToken,
            String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("search")
                .request()
                .cookie(RequestContext.SESSION_COOKIE, sessionToken)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(streamRequest));
    }

    public Response callUpdateTraceWithCookie(UUID id, TraceUpdate traceUpdate, String sessionToken,
            String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path(id.toString())
                .request()
                .cookie(RequestContext.SESSION_COOKIE, sessionToken)
                .header(WORKSPACE_HEADER, workspaceName)
                .method(HttpMethod.PATCH, Entity.json(traceUpdate));
    }

    public Response callDeleteTraceWithCookie(UUID id, String sessionToken, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path(id.toString())
                .request()
                .cookie(RequestContext.SESSION_COOKIE, sessionToken)
                .header(WORKSPACE_HEADER, workspaceName)
                .delete();
    }

    public Response callGetTraceThreadsWithSorting(UUID projectId, List<SortingField> sortingFields, String apiKey,
            String workspaceName) {
        WebTarget target = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("threads")
                .queryParam("project_id", projectId);

        if (CollectionUtils.isNotEmpty(sortingFields)) {
            target = target.queryParam("sorting",
                    URLEncoder.encode(JsonUtils.writeValueAsString(sortingFields), StandardCharsets.UTF_8));
        }

        return target
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
    }

    public Response callDeleteTrace(UUID id, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .delete();
    }

    public Response callGetTracesWithQueryParams(String apiKey, String workspaceName, Map<String, String> queryParams) {
        WebTarget target = addQueryParameters(client.target(RESOURCE_PATH.formatted(baseURI)), queryParams);

        return target
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
    }

    public Response callGetTraceThreadsWithQueryParams(String projectName, UUID projectId,
            Map<String, String> queryParams, String apiKey, String workspaceName) {
        WebTarget target = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("threads");

        if (projectId != null) {
            target = target.queryParam("project_id", projectId);
        }

        if (projectName != null) {
            target = target.queryParam("project_name", projectName);
        }

        target = addQueryParameters(target, queryParams);

        return target
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
    }

    public Response callSearchTraceThreadsWithRequest(TraceThreadSearchStreamRequest request, String apiKey,
            String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("threads")
                .path("search")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request));
    }

    public List<TraceThread> searchTraceThreadsStream(TraceThreadSearchStreamRequest request, String apiKey,
            String workspaceName) {
        try (var actualResponse = callSearchTraceThreadsWithRequest(request, apiKey, workspaceName)) {
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
            return getStreamedTraceThreads(actualResponse);
        }
    }

    public Response callGetById(UUID id, String apiKey, String workspaceName, Map<String, String> queryParams) {
        WebTarget target = addQueryParameters(
                client.target(RESOURCE_PATH.formatted(baseURI)).path(id.toString()),
                queryParams);

        return target
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
    }

    public Response callGetFeedbackScoresToNames(UUID projectId, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("feedback-scores")
                .path("names")
                .queryParam("project_id", projectId)
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
    }

    public Response callRetrieveThreadResponse(TraceThreadIdentifier identifier, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("threads")
                .path("retrieve")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(identifier));
    }

    public Response callRetrieveThreadResponseWithCookie(TraceThreadIdentifier identifier, String sessionToken,
            String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("threads")
                .path("retrieve")
                .request()
                .cookie(RequestContext.SESSION_COOKIE, sessionToken)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(identifier));
    }

    public Response callSearchTracesStream(TraceSearchStreamRequest streamRequest, String apiKey,
            String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("search")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(streamRequest));
    }

    public Response callGetWithPath(String pathSuffix, String queryParamKey, String queryParamValue, String apiKey,
            String workspaceName) {
        WebTarget target = addPathSegments(client.target(RESOURCE_PATH.formatted(baseURI)), pathSuffix);

        // Add query parameter if provided
        if (queryParamKey != null && queryParamValue != null) {
            target = target.queryParam(queryParamKey, queryParamValue);
        }

        return target
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
    }

    public Response callPutToPath(String pathSuffix, Object body, String apiKey, String workspaceName) {
        WebTarget target = addPathSegments(client.target(RESOURCE_PATH.formatted(baseURI)), pathSuffix);

        return target
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(body));
    }

    public Response callPostToPath(String pathSuffix, Object body, String apiKey, String workspaceName) {
        WebTarget target = addPathSegments(client.target(RESOURCE_PATH.formatted(baseURI)), pathSuffix);

        return target
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(body));
    }

    public Response callDeleteTraceThreads(DeleteTraceThreads threadIds, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("threads")
                .path("delete")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(threadIds));
    }

}
