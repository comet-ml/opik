package com.comet.opik.domain;

import com.comet.opik.infrastructure.auth.RequestContext;
import lombok.NonNull;

/**
 * Whether a per-organization feature is on for the caller's workspace. Asked by the endpoints that need a feature,
 * never during authentication, so a feature costs nothing on the path every request takes.
 */
public interface WorkspaceFeaturesService {

    boolean isEnabled(@NonNull RequestContext caller, @NonNull WorkspaceFeature feature);
}
