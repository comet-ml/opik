package com.comet.opik.domain.topics;

import lombok.NonNull;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * A plan's one deadline, on the monotonic clock, for all its phases: past it, the plan queues nothing more, and a count
 * still running is cut. So a plan ends within {@code topics.runs.planBudget} plus the queue write in flight, inside
 * the leader's lease.
 */
record PlanDeadline(long endNanos) {

    static PlanDeadline after(@NonNull Duration budget) {
        return new PlanDeadline(System.nanoTime() + budget.toNanos());
    }

    /** This deadline, or {@code fromNow} from now when that comes first. */
    PlanDeadline atMost(@NonNull Duration fromNow) {
        long end = System.nanoTime() + Math.max(0, fromNow.toNanos());
        return end - endNanos < 0 ? new PlanDeadline(end) : this;
    }

    boolean passed() {
        return System.nanoTime() - endNanos >= 0;
    }

    Duration left() {
        return Duration.ofNanos(Math.max(0, endNanos - System.nanoTime()));
    }

    /** Emits once the deadline passes. */
    Mono<Long> timer() {
        return Mono.defer(() -> Mono.delay(left()));
    }

    /** {@code step}, cancelled and empty when the deadline passes first. */
    <T> Mono<T> cut(@NonNull Mono<T> step) {
        return step.takeUntilOther(timer());
    }
}
