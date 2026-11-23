package com.comet.opik.domain.topics;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.NonNull;

import java.util.function.Function;

/**
 * One step of a nightly update, as a pure step machine: either {@link Ask} (the update needs one LLM answer to go on) or
 * {@link Done} (its result). The caller makes each call and feeds the answer to {@link Ask#next}; the algorithm does no
 * I/O itself. {@link TopicsRebuildService} drives it reactively, a test with scripted answers.
 *
 * @param <T> the result
 */
public sealed interface NightStep<T> {

    /**
     * The update asks for one LLM call. {@code next} takes the answer's {@code output} when it is a JSON object, or null
     * for an answer that is not (a bad answer, counted {@code bad_answer}); the algorithm decides what null means. A
     * failed call (timeout, error, schema violation) never reaches {@code next}: it fails the run.
     */
    @Builder(toBuilder = true)
    record Ask<T>(@NonNull LlmRequest request, @NonNull Function<JsonNode, NightStep<T>> next) implements NightStep<T> {
    }

    @Builder(toBuilder = true)
    record Done<T>(@NonNull T result) implements NightStep<T> {
    }

    static <T> NightStep<T> done(@NonNull T result) {
        return Done.<T>builder().result(result).build();
    }

    static <T> NightStep<T> ask(@NonNull LlmRequest request, @NonNull Function<JsonNode, NightStep<T>> next) {
        return Ask.<T>builder().request(request).next(next).build();
    }

    /** This step, then {@code after} on its result. */
    default <R> NightStep<R> then(@NonNull Function<? super T, NightStep<R>> after) {
        return switch (this) {
            case Done<T> done -> after.apply(done.result());
            case Ask<T> ask -> ask(ask.request(), answer -> ask.next().apply(answer).then(after));
        };
    }

    default <R> NightStep<R> map(@NonNull Function<? super T, R> after) {
        return then(result -> done(after.apply(result)));
    }
}
