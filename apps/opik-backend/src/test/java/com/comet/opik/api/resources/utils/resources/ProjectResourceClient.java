package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.FeedbackScoreNames;
import com.comet.opik.api.Project;
import com.comet.opik.api.ProjectStatsSummary;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.core5.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.UUID;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public class ProjectResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/projects";

    private final ClientSupport client;
    private final String baseURI;
    private final PodamFactory podamFactory;

    public UUID createProject(String projectName, String apiKey, String workspaceName) {

        var project = podamFactory.manufacturePojo(Project.class).toBuilder()
                .name(projectName)
                .build();

        return createProject(project, apiKey, workspaceName);
    }

    public UUID createProject(Project project, String apiKey, String workspaceName) {

        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(project))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);

            return TestUtils.getIdFromLocation(response.getLocation());
        }
    }

    public Project getProject(UUID projectId, String apiKey, String workspaceName) {

        try (var response = client.target(RESOURCE_PATH.formatted(baseURI) + "/" + projectId)
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);

            return response.readEntity(Project.class);
        }
    }

    public Response callGetprojectByName(String projectName, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .queryParam("name", projectName)
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .get();
    }

    public Project getByName(String projectName, String apiKey, String workspaceName) {

        try (var response = callGetprojectByName(projectName, apiKey, workspaceName)) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);

            return response.readEntity(Project.ProjectPage.class)
                    .content()
                    .stream()
                    .findFirst()
                    .orElseThrow();
        }
    }

    public FeedbackScoreNames findFeedbackScoreNames(String projectIdsQueryParam, String apiKey, String workspaceName) {
        WebTarget webTarget = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("feedback-scores")
                .path("names")
                .queryParam("project_ids", projectIdsQueryParam);

        try (var actualResponse = webTarget
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .get()) {

            // then
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(org.apache.http.HttpStatus.SC_OK);

            return actualResponse.readEntity(FeedbackScoreNames.class);
        }
    }

    public ProjectStatsSummary getProjectStatsSummary(String projectName, @NonNull String apiKey,
            @NonNull String workspaceName) {
        WebTarget webTarget = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("/stats");

        if (StringUtils.isEmpty(projectName)) {
            webTarget = webTarget.queryParam("name", projectName);
        }

        Response actualResponse = webTarget
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();

        assertThat(actualResponse.getStatus()).isEqualTo(org.apache.http.HttpStatus.SC_OK);

        return actualResponse.readEntity(ProjectStatsSummary.class);
    }

}
