package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.BiInformationResponse;
import com.comet.opik.api.SpansCountResponse;
import com.comet.opik.api.TraceCountResponse;
import com.comet.opik.api.UsageByWorkspaceProjectUserResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.hc.core5.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Client for the internal usage endpoints, which back the BI events and the usage billing pull. They take no
 * authentication, so no api key or workspace name is threaded through.
 */
@RequiredArgsConstructor
public class UsageResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/internal/usage";

    private final ClientSupport client;
    private final String baseURI;

    public TraceCountResponse getWorkspaceTraceCounts() {
        return get("workspace-trace-counts", TraceCountResponse.class);
    }

    public SpansCountResponse getWorkspaceSpanCounts() {
        return get("workspace-span-counts", SpansCountResponse.class);
    }

    public UsageByWorkspaceProjectUserResponse getWorkspaceSpanCountsBreakdown() {
        return get("workspace-span-counts-breakdown", UsageByWorkspaceProjectUserResponse.class);
    }

    /**
     * @param entityType the entity the BI events are reported for: {@code traces}, {@code spans},
     *                   {@code experiments} or {@code datasets}
     */
    public BiInformationResponse getBiInformation(@NonNull String entityType) {
        return get("bi-%s".formatted(entityType), BiInformationResponse.class);
    }

    private <T> T get(String path, Class<T> responseType) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(path)
                .request()
                .get()) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            assertThat(response.hasEntity()).isTrue();
            return response.readEntity(responseType);
        }
    }
}
