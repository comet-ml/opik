package com.comet.opik.domain.topics;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.NonNull;

import java.util.function.Function;

/**
 * One step of a nightly update, as a pure step machine: either {@link Ask} (the update needs one LLM answer to go on) or
 * {@link Done} (its result). The caller makes each call and feeds the answer to {@link Ask#next}; the algorithm does no
 * I/O itself. {@link TopicsRunService} drives it reactively, a test with scripted answers.
 *
 * @param <T> the result
 */
sealed interface NightStep<T> {

    /**
     * The update asks for one LLM call. {@code next} takes the answer's {@code output} when it is a JSON object, or null
     * for an answer that is not (a bad answer, counted {@code bad_answer}); the algorithm decides what null means. A
     * failed call (timeout, error, schema violation) fails the run, unless the call is {@code optional}: then
     * {@code next} gets null, as for a bad answer.
     */
    @Builder(toBuilder = true)
    record Ask<T>(@NonNull LlmRequest request, @NonNull Function<JsonNode, NightStep<T>> next, boolean optional)
            implements
                NightStep<T> {
    }

    @Builder(toBuilder = true)
    record Done<T>(@NonNull T result) implements NightStep<T> {
    }

    static <T> NightStep<T> done(T result) {
        return Done.<T>builder().result(result).build();
    }

    static <T> NightStep<T> ask(LlmRequest request, Function<JsonNode, NightStep<T>> next) {
        return Ask.<T>builder().request(request).next(next).build();
    }

    /** {@link #ask} for a call the step can do without: a failed call reaches {@code next} as null. */
    static <T> NightStep<T> optional(LlmRequest request, Function<JsonNode, NightStep<T>> next) {
        return Ask.<T>builder().request(request).next(next).optional(true).build();
    }

    /** This step, then {@code after} on its result. */
    default <R> NightStep<R> then(Function<? super T, NightStep<R>> after) {
        return switch (this) {
            case Done<T> done -> after.apply(done.result());
            case Ask<T> ask -> Ask.<R>builder().request(ask.request())
                    .next(answer -> ask.next().apply(answer).then(after)).optional(ask.optional()).build();
        };
    }

    default <R> NightStep<R> map(Function<? super T, R> after) {
        return then(result -> done(after.apply(result)));
    }
}
