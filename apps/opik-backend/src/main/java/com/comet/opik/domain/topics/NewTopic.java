package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

/** A new topic of a {@link FirstBuild}, before it has an id. */
@Builder(toBuilder = true)
public record NewTopic(@NonNull String name, @NonNull String definition) {
}
