package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

import java.util.List;

/** A new topic of a {@link FirstBuild}, before it has an id, with the prototype vectors it is saved with. */
@Builder(toBuilder = true)
public record NewTopic(@NonNull String name, @NonNull String definition, @NonNull List<float[]> prototypes) {
}
