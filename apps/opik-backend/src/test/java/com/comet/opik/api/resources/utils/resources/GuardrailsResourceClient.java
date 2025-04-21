package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.GuardrailBatch;
import com.comet.opik.api.GuardrailBatchItem;
import com.comet.opik.podam.PodamFactoryUtils;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import lombok.RequiredArgsConstructor;
import org.apache.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public class GuardrailsResourceClient {
    private static final String RESOURCE_PATH = "%s/v1/private/guardrails";

    private final ClientSupport client;
    private final String baseURI;
    private final PodamFactory podamFactory;

    public void addBatch(List<GuardrailBatchItem> guardrails, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(new GuardrailBatch(guardrails)))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    public List<GuardrailBatchItem> generateGuardrailsForTrace(UUID traceId, UUID spanId, String projectName) {
        return PodamFactoryUtils.manufacturePojoList(podamFactory, GuardrailBatchItem.class).stream()
                .map(guardrail -> guardrail.toBuilder()
                        .entityId(traceId)
                        .secondaryId(spanId)
                        .projectName(projectName)
                        .build())
                // deduplicate by guardrail name
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(
                                GuardrailBatchItem::name,
                                Function.identity(),
                                (existing, replacement) -> existing),
                        map -> new ArrayList<>(map.values())));
    }
}
