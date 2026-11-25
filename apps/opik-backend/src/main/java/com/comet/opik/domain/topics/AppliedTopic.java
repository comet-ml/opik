package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

import java.util.List;

/** A topic {@link AuthorApplier} creates, with its groups in the order the answer listed them. */
@Builder(toBuilder = true)
public record AppliedTopic(@NonNull String name, @NonNull String definition, @NonNull List<Integer> groups) {
}
