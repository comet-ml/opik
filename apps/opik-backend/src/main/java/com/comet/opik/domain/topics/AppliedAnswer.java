package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

import java.util.List;

/** {@link AuthorApplier}'s result: the new topics in creation order, and the groups that went to Other, ascending. */
@Builder(toBuilder = true)
public record AppliedAnswer(@NonNull List<AppliedTopic> topics, @NonNull List<Integer> other) {
}
