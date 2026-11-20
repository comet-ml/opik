package com.comet.opik.domain.ailabels;

import lombok.NonNull;

import java.util.Set;
import java.util.stream.Stream;

/**
 * The placeholders of a generate task's {@code prompt.user}: each {@code required} one exactly once, each
 * {@code optional} one at most once, each {@code repeated} one at least once; none of them in
 * {@code prompt.system}.
 */
public record TaskPlaceholders(@NonNull Set<String> required, @NonNull Set<String> optional,
        @NonNull Set<String> repeated) {

    /** ai-labels' rule: the trace's state, and its earlier turns when the task wants them. */
    public static final TaskPlaceholders AI_LABELS = new TaskPlaceholders(Set.of(AiLabelTask.STATE_PLACEHOLDER),
            Set.of(AiLabelTask.HISTORY_PLACEHOLDER), Set.of());

    public TaskPlaceholders {
        required = Set.copyOf(required);
        optional = Set.copyOf(optional);
        repeated = Set.copyOf(repeated);
    }

    Stream<String> all() {
        return Stream.of(required, optional, repeated).flatMap(Set::stream).sorted();
    }
}
