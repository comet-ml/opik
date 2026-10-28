package com.comet.opik.domain.topics;

import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** A nightly run that failed for a known reason; {@link Code} is what {@code topic_runs.error_code} records. */
@Getter
public class TopicsRunException extends RuntimeException {

    private final @NonNull Code code;

    public TopicsRunException(@NonNull Code code, @NonNull String message) {
        this(code, message, null);
    }

    public TopicsRunException(@NonNull Code code, @NonNull String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    @Getter
    @RequiredArgsConstructor
    public enum Code {
        /** The Qwen call ran out of its deadline. */
        QWEN_TIMEOUT("qwen_timeout"),
        /** The Qwen call failed for another reason (an error answer after the retries, a 4xx). */
        QWEN_ERROR("qwen_error"),
        /** The answer has no JSON object or breaks the schema. */
        QWEN_BAD_ANSWER("qwen_bad_answer"),
        WINDOW_READ("window_read"),
        /** A check before the save failed: nothing is written. */
        INVARIANT("invariant"),
        /** The run outlasted {@code topics.nightly.projectTimeout}. */
        TIMEOUT("timeout"),
        SAVE("save"),
        INTERNAL("internal");

        private final String value;
    }
}
