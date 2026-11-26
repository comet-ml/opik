package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

import java.util.Set;
import java.util.UUID;

/** The list a nightly update read: its state, the run that saved it, and the ids of its stored topics. */
@Builder(toBuilder = true)
record NightList(@NonNull NightState state, @NonNull UUID listRunId, @NonNull Set<UUID> topicIds) {
}
