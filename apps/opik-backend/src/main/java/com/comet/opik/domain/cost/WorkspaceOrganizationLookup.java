package com.comet.opik.domain.cost;

import com.google.inject.ImplementedBy;
import reactor.core.publisher.Mono;

/** The organization a workspace belongs to, or empty when Opik doesn't know it. */
@ImplementedBy(NoWorkspaceOrganizationLookup.class)
public interface WorkspaceOrganizationLookup {

    Mono<String> organizationOf(String workspaceId);
}

// Opik learns organizations from Comet, which doesn't send them yet. Until then every workspace prices with the
// built-in list only.
class NoWorkspaceOrganizationLookup implements WorkspaceOrganizationLookup {

    @Override
    public Mono<String> organizationOf(String workspaceId) {
        return Mono.empty();
    }
}
