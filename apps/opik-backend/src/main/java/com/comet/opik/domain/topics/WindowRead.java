package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicVectorsDAO.Candidate;
import jakarta.annotation.Nullable;
import lombok.NonNull;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

/**
 * One window read, of a workspace or of one project: the candidates, the newest slice's first and each slice's oldest
 * first, and whether the read saw the whole window: every slice read, under the limit in all.
 */
record WindowRead(@NonNull List<Candidate> candidates, boolean complete) {

    /**
     * Without a slice size it is one query. With one, the window's traces are read by creation time in half-open slices
     * of that size, counted back from {@code find}'s upper bound, the newest first and without an upper bound, down to
     * its {@code createdAfter}: every trace is in exactly one slice, and each query's cost follows its slice, not the
     * window. The read stops at {@code find}'s limit, or when the tick has no time for another read and one embed call
     * after it.
     */
    static Mono<WindowRead> read(@NonNull TopicVectorsDAO dao, @NonNull TopicVectorsFind find,
            @Nullable Duration sliceSize, @NonNull TickBudget budget, @NonNull Duration maxExecutionTime,
            @NonNull Duration reserve) {
        return next(dao, find, sliceSize, budget, maxExecutionTime, reserve, null, List.of());
    }

    private static Mono<WindowRead> next(TopicVectorsDAO dao, TopicVectorsFind find, Duration sliceSize,
            TickBudget budget, Duration maxExecutionTime, Duration reserve, Instant createdBefore,
            List<Candidate> found) {
        return Mono.defer(() -> {
            if (!budget.hasTimeForRead(reserve)) {
                return Mono.just(new WindowRead(found, false));
            }
            var sliceStart = sliceSize == null
                    ? find.createdAfter()
                    : (createdBefore == null ? find.upperBound() : createdBefore).minus(sliceSize);
            var createdAfter = sliceStart.isAfter(find.createdAfter()) ? sliceStart : find.createdAfter();
            int remaining = find.limit() - found.size();
            return dao.findUnvectored(find.toBuilder()
                    .createdAfter(createdAfter)
                    .createdBefore(createdBefore)
                    .limit(remaining)
                    .maxExecutionSeconds(budget.readSeconds(maxExecutionTime, reserve))
                    .build())
                    .collectList()
                    .flatMap(rows -> {
                        var all = Stream.concat(found.stream(), rows.stream()).toList();
                        if (rows.size() >= remaining) {
                            return Mono.just(new WindowRead(all, false));
                        }
                        if (!createdAfter.isAfter(find.createdAfter())) {
                            return Mono.just(new WindowRead(all, true));
                        }
                        return next(dao, find, sliceSize, budget, maxExecutionTime, reserve, createdAfter, all);
                    });
        });
    }
}
