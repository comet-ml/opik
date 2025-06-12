package com.comet.opik.api.events;

import java.util.Set;
import java.util.UUID;

public record TraceThreadsReopened(Set<UUID> ids) {
}
