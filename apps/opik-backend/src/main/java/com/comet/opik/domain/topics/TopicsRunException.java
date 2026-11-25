package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicProject.RunError;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Getter;
import lombok.NonNull;

/** A run that failed for a known reason; the project's state records its {@link RunError}. */
@Getter
public class TopicsRunException extends RuntimeException {

    private final @NonNull RunError code;
    /** What the run had read when it failed: each step it got past adds its stats. */
    private final @NonNull ObjectNode stats = JsonUtils.createObjectNode();

    public TopicsRunException(@NonNull RunError code, @NonNull String message) {
        this(code, message, null);
    }

    public TopicsRunException(@NonNull RunError code, @NonNull String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    /** {@code error} as a run failure ({@code internal} when it is not one), carrying {@code stats} too. */
    static TopicsRunException withStats(@NonNull Throwable error, @NonNull ObjectNode stats) {
        var failure = error instanceof TopicsRunException known
                ? known
                : new TopicsRunException(RunError.INTERNAL, "the run failed", error);
        failure.stats.setAll(stats);
        return failure;
    }
}
