package com.comet.opik.infrastructure.bi;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.bi.ActiveClientsService.ClientType;
import com.comet.opik.infrastructure.http.CorsFactory;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.regex.Pattern;

/**
 * Feeds {@link ActiveClientsService} from the anonymous client identifiers the SDKs and the frontend send. Only
 * while the usage report is enabled, as nothing else reads the counts.
 */
@jakarta.ws.rs.ext.Provider
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ActiveClientsFilter implements ContainerRequestFilter {

    // The shapes the clients send: a SHA-256 hex digest from the SDKs, a UUID from the frontend.
    // Anything else is ignored rather than counted.
    private static final Pattern SDK_CLIENT_ID = Pattern.compile("^[0-9a-f]{64}$");
    private static final Pattern BROWSER_ID = Pattern
            .compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final @NonNull OpikConfiguration config;
    private final @NonNull ActiveClientsService activeClientsService;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        if (!config.getUsageReport().isEnabled()) {
            return;
        }

        record(requestContext.getHeaderString(CorsFactory.OPIK_CLIENT_ID_HEADER), SDK_CLIENT_ID, ClientType.SDK);
        record(requestContext.getHeaderString(CorsFactory.OPIK_BROWSER_ID_HEADER), BROWSER_ID, ClientType.UI);
    }

    private void record(String id, Pattern format, ClientType type) {
        if (id != null && format.matcher(id).matches()) {
            activeClientsService.record(type, id.toLowerCase());
        }
    }
}
