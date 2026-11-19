package com.comet.opik.api.resources.utils;

import com.comet.opik.api.ProjectTopics;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.ailabels.AiLabelWorkspaceDAO;
import com.comet.opik.domain.topics.Topic;
import com.comet.opik.domain.topics.TopicProjectState;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.github.tomakehurst.wiremock.WireMockServer;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.TreeSet;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;

/** Callers, projects, opt-in, topics and project state for the resource tests of the topics read API and filter. */
@RequiredArgsConstructor
public class TopicsReadTestUtils {

    public static final String RESOURCE_PATH = "%s/v1/private/topics";

    private static final String USER = "user-" + UUID.randomUUID();
    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    private final @NonNull WireMockServer wireMock;
    private final @NonNull ClientSupport client;
    private final @NonNull String baseUrl;
    private final @NonNull ProjectResourceClient projectClient;
    private final @NonNull TransactionTemplate template;
    private final @NonNull OpikConfiguration opikConfig;

    public record Caller(String apiKey, String workspaceName, String workspaceId) {
    }

    public Caller caller() {
        var caller = new Caller(UUID.randomUUID().toString(), "ws-" + UUID.randomUUID(),
                UUID.randomUUID().toString());
        AuthTestUtils.mockTargetWorkspace(wireMock, caller.apiKey(), caller.workspaceName(), caller.workspaceId(),
                USER);
        return caller;
    }

    public UUID project(Caller caller) {
        return projectClient.createProject("topics-" + UUID.randomUUID(), caller.apiKey(), caller.workspaceName());
    }

    /** Enabled in ai_label_workspaces, and added to topics.enabledWorkspaces as a Helm change would. */
    public void optIn(String workspaceId) {
        template.inTransaction(WRITE, handle -> {
            handle.attach(AiLabelWorkspaceDAO.class).upsert(workspaceId, true, "test");
            return null;
        });
        var topics = opikConfig.getTopics();
        var listed = new TreeSet<>(topics.enabledWorkspaceIds());
        listed.add(workspaceId);
        topics.setEnabledWorkspaces(String.join(",", listed));
    }

    public Invocation.Builder request(Caller caller, UUID projectId) {
        return client.target(RESOURCE_PATH.formatted(baseUrl))
                .queryParam("project_id", projectId)
                .request()
                .header(HttpHeaders.AUTHORIZATION, caller.apiKey())
                .header(RequestContext.WORKSPACE_HEADER, caller.workspaceName());
    }

    public ProjectTopics get(Caller caller, UUID projectId) {
        try (var response = request(caller, projectId).get()) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.OK.getStatusCode());
            return response.readEntity(ProjectTopics.class);
        }
    }

    /** The state of a project a run saved. */
    public static TopicProjectState savedState(String workspaceId, UUID projectId) {
        var today = LocalDate.now(ZoneOffset.UTC);
        return TopicProjectState.builder()
                .projectId(projectId)
                .workspaceId(workspaceId)
                .rebuilds(1)
                .firstBuiltAt(today.minusDays(20).atTime(2, 0).toInstant(ZoneOffset.UTC))
                .lastRunId(ID_GENERATOR.generateId())
                .lastSuccessAt(Instant.now().truncatedTo(ChronoUnit.MICROS))
                .windowUntil(today.atStartOfDay().toInstant(ZoneOffset.UTC))
                .build();
    }

    public static Topic topic(String workspaceId, UUID projectId, LocalDate born, LocalDate retiredAt) {
        var id = UUID.randomUUID();
        return Topic.builder()
                .id(id)
                .workspaceId(workspaceId)
                .projectId(projectId)
                .name("Topic " + id)
                .definition("Definition of " + id)
                .born(born)
                .retiredAt(retiredAt)
                .retiredReason(retiredAt == null ? null : "merged")
                .build();
    }
}
