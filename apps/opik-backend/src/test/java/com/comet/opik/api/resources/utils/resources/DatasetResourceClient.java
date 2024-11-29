package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.ws.rs.client.Entity;
import lombok.RequiredArgsConstructor;
import org.apache.http.HttpStatus;
import org.testcontainers.shaded.com.google.common.net.HttpHeaders;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public class DatasetResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/datasets";

    private final ClientSupport client;
    private final String baseURI;
    private final PodamFactory podamFactory;

    public UUID create(Dataset dataset, String apiKey, String workspaceName) {

        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(dataset))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
            return TestUtils.getIdFromLocation(response.getLocation());
        }
    }
}
