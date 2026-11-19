package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

import java.util.List;

/** One topic of an {@link AuthorAnswer}: its name, definition and the group ids assigned to it, in answer order. */
@Builder(toBuilder = true)
public record AuthorTopic(@NonNull String name, @NonNull String definition, @NonNull List<Integer> clusters) {
}
