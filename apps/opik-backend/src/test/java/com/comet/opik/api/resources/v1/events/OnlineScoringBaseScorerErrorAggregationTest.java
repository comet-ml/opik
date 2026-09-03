package com.comet.opik.api.resources.v1.events;

import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

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
 */
@DisplayName("Trace-thread sibling error aggregation")
class OnlineScoringBaseScorerErrorAggregationTest {

    private static final Throwable RETRYABLE = new InternalServerErrorException("provider had a bad moment");
    private static final Throwable PERMANENT = new ClientErrorException("rejected, and will be every time", 400);

    @Test
    @DisplayName("A retryable sibling wins even when a permanent one arrives first")
    void retryableWinsWhenPermanentArrivesFirst() {
        // The ordering that used to lose data: getFirst() would have returned the ClientErrorException and
        // the retryable sibling would never have been retried.
        assertThat(OnlineScoringBaseScorer.representativeError(List.of(PERMANENT, RETRYABLE)))
                .isSameAs(RETRYABLE);
    }

    @Test
    @DisplayName("A retryable sibling still wins when it arrives first")
    void retryableWinsWhenItArrivesFirst() {
        assertThat(OnlineScoringBaseScorer.representativeError(List.of(RETRYABLE, PERMANENT)))
                .isSameAs(RETRYABLE);
    }

    @Test
    @DisplayName("All-permanent stays permanent, so a hopeless batch is still dropped rather than cycling")
    void allPermanentStaysPermanent() {
        var otherPermanent = new ClientErrorException("also hopeless", 403);
        assertThat(OnlineScoringBaseScorer.representativeError(List.of(PERMANENT, otherPermanent)))
                .isSameAs(PERMANENT);
    }

    @Test
    @DisplayName("A single failure is passed through unchanged, whichever kind it is")
    void singleFailurePassesThrough() {
        assertThat(OnlineScoringBaseScorer.representativeError(List.of(PERMANENT))).isSameAs(PERMANENT);
        assertThat(OnlineScoringBaseScorer.representativeError(List.of(RETRYABLE))).isSameAs(RETRYABLE);
    }
}
