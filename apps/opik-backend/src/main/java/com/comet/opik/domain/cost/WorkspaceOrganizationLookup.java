package com.comet.opik.domain.cost;

import com.google.inject.ImplementedBy;
import lombok.NonNull;
import reactor.core.publisher.Mono;

/** The organization a workspace belongs to, or empty when Opik doesn't know it. */
@ImplementedBy(NoWorkspaceOrganizationLookup.class)
public interface WorkspaceOrganizationLookup {

    Mono<String> organizationOf(@NonNull String workspaceId);
}

// Opik learns organizations from Comet, which doesn't send them yet. Until then every workspace prices with the
// built-in list only, and organization price writes are refused.
class NoWorkspaceOrganizationLookup implements WorkspaceOrganizationLookup {

    @Override
    public Mono<String> organizationOf(@NonNull String workspaceId) {
        return Mono.empty();
    }
}
