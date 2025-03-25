package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentItemsBatch;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.apache.http.HttpStatus;
import org.testcontainers.shaded.com.google.common.net.HttpHeaders;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public class ExperimentResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/experiments";

    private final ClientSupport client;
    private final String baseURI;
    private final PodamFactory podamFactory;

    public Experiment.ExperimentBuilder createPartialExperiment() {
        return podamFactory.manufacturePojo(Experiment.class).toBuilder()
                .promptVersion(null)
                .promptVersions(null);
    }

    public List<Experiment> generateExperimentList() {
        return PodamFactoryUtils.manufacturePojoList(podamFactory, Experiment.class).stream()
                .map(experiment -> experiment.toBuilder().promptVersion(null).promptVersions(null).build())
                .toList();
    }

    public UUID create(Experiment experiment, String apiKey, String workspaceName) {

        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(experiment))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
            return TestUtils.getIdFromLocation(response.getLocation());
        }
    }

    public UUID createExperiment(String apiKey, String workspaceName) {
        Experiment experiment = podamFactory.manufacturePojo(Experiment.class).toBuilder()
                .promptVersion(null)
                .promptVersions(null)
                .build();

        return create(experiment, apiKey, workspaceName);
    }

    public void createExperimentItem(Set<ExperimentItem> experimentItems, String apiKey, String workspaceName) {
        try (var response = callCreateExperimentItem(experimentItems, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    public Response callCreateExperimentItem(Set<ExperimentItem> experimentItems, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("items")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(new ExperimentItemsBatch(experimentItems)));
    }
}
