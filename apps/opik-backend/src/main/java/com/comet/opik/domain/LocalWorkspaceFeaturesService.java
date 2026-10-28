package com.comet.opik.domain;

import com.comet.opik.infrastructure.auth.RequestContext;
import lombok.NonNull;

/**
 * With authentication disabled there is no platform and no organization to switch a feature on, so every feature
 * is on and the deployment's own service toggles alone decide.
 */
public class LocalWorkspaceFeaturesService implements WorkspaceFeaturesService {

    @Override
    public boolean isEnabled(@NonNull RequestContext caller, @NonNull WorkspaceFeature feature) {
        return true;
    }
}
