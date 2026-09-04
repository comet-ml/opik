package com.comet.opik.api.resources.v1.events;

import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression for the sibling-aggregation hazard caught in review of OPIK-8240.
 *
 * <p>A trace-thread message fans out over many thread ids, and the single error re-emitted after the
 * {@code flatMap} decides the fate of the whole stream entry. While every provider failure was a blanket
 * 500 the choice was immaterial -- all siblings were retryable, so picking arbitrarily
 * ({@code errors.getFirst()}) could not change the outcome. Splitting permanent from transient made that
 * arbitrary pick a race: a {@code ClientErrorException} arriving first would ack and remove the entry,
 * taking any retryable sibling with it, unretried and unrecorded.
 *
 * <p>Exercised through {@link Flux#reduce} rather than by calling the accumulator directly, so the tests
 * pin the behaviour as the scorers actually use it -- including that an empty sequence stays empty, which
 * is the no-failures path.
 */
@DisplayName("Trace-thread sibling error aggregation")
class OnlineScoringBaseScorerErrorAggregationTest {

    private static final Throwable RETRYABLE = new InternalServerErrorException("provider had a bad moment");
    private static final Throwable OTHER_RETRYABLE = new InternalServerErrorException("and another");
    private static final Throwable PERMANENT = new ClientErrorException("rejected, and will be every time", 400);
    private static final Throwable OTHER_PERMANENT = new ClientErrorException("also hopeless", 403);

    private static Stream<Arguments> siblingFailures() {
        return Stream.of(
                // The ordering that used to lose data: getFirst() would have returned the permanent one and
                // the retryable sibling would never have been retried.
                Arguments.of("permanent first, retryable second", List.of(PERMANENT, RETRYABLE), RETRYABLE),
                // Control for the above -- the same outcome must not depend on arrival order.
                Arguments.of("retryable first, permanent second", List.of(RETRYABLE, PERMANENT), RETRYABLE),
                // Order-stability: the FIRST retryable wins, not merely any retryable.
                Arguments.of("retryable before another retryable",
                        List.of(PERMANENT, RETRYABLE, OTHER_RETRYABLE), RETRYABLE),
                // A hopeless batch is still dropped rather than cycling to maxRetries pointlessly.
                Arguments.of("all permanent keeps the first", List.of(PERMANENT, OTHER_PERMANENT), PERMANENT),
                // Single failures pass through whichever kind they are.
                Arguments.of("lone permanent", List.of(PERMANENT), PERMANENT),
                Arguments.of("lone retryable", List.of(RETRYABLE), RETRYABLE));
    }

    @ParameterizedTest(name = "{0} -> {2}")
    @MethodSource("siblingFailures")
    @DisplayName("A retryable sibling represents the batch whenever there is one, whatever the arrival order")
    void reducingSiblingFailuresPrefersRetryable(String testName, List<Throwable> failures, Throwable expected) {
        // isSameAs, not isEqualTo: the actual Throwable instance must propagate so its stack trace and
        // cause chain survive to BaseRedisSubscriber, which classifies on the concrete type.
        StepVerifier.create(Flux.fromIterable(failures).reduce(OnlineScoringBaseScorer::preferRetryable))
                .assertNext(chosen -> assertThat(chosen).isSameAs(expected))
                .verifyComplete();
    }

    @ParameterizedTest(name = "no failures -> empty ({0})")
    @MethodSource("emptyCase")
    @DisplayName("No failures reduces to empty, which is the success path rather than an error")
    void noFailuresReducesToEmpty(String testName, List<Throwable> failures) {
        StepVerifier.create(Flux.fromIterable(failures).reduce(OnlineScoringBaseScorer::preferRetryable))
                .verifyComplete();
    }

    private static Stream<Arguments> emptyCase() {
        return Stream.of(Arguments.of("every thread id succeeded", List.<Throwable>of()));
    }
}
