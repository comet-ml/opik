package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicProject.RunError;
import lombok.Getter;
import lombok.NonNull;

/** A run that failed for a known reason; the project's state records its {@link RunError}. */
@Getter
public class TopicsRunException extends RuntimeException {

    private final @NonNull RunError code;

    public TopicsRunException(@NonNull RunError code, @NonNull String message) {
        this(code, message, null);
    }

    public TopicsRunException(@NonNull RunError code, @NonNull String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }
}
