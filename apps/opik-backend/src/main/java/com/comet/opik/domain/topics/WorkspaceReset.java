package com.comet.opik.domain.topics;

import lombok.NonNull;

import java.util.List;
import java.util.UUID;

/** What a workspace's reset by hand did: the projects whose lists it forgot, and those it skipped for a run in flight. */
public record WorkspaceReset(@NonNull List<UUID> reset, @NonNull List<UUID> inFlight) {
}
