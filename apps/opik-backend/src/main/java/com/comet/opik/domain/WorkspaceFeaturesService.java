package com.comet.opik.domain;

import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import lombok.NonNull;

/**
 * Whether a per-organization feature is on for the caller's workspace. Asked by the endpoints that need a feature,
 * after authentication and apart from it, so a feature costs nothing on the path every request takes.
 */
public interface WorkspaceFeaturesService {

    /**
     * @throws jakarta.ws.rs.ServiceUnavailableException when the platform can't tell, so a request fails with 503
     *                                                   instead of reading an outage as "not enabled"
     */
    boolean isEnabled(@NonNull RequestContext caller, @NonNull HttpHeaders request, @NonNull WorkspaceFeature feature);
}
