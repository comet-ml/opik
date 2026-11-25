package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One workspace's topic on arrival in one vectors tick, as its steps leave it: each step of
 * {@link TopicsArrivalService} returns the next value, and the vectors tick carries it from one workspace read to the next.
 *
 * @param on the workspace is listed at the arrival stage or a later one
 * @param read the projects whose saved list the last lists read read
 * @param labellers of the projects read, those whose list has a topic
 * @param spent the time the workspace's steps took this tick
 * @param stopped a step failed: arrival does nothing more for the workspace this tick
 */
@Builder(toBuilder = true)
record ArrivalSteps(boolean on, @NonNull Set<UUID> read, @NonNull Map<UUID, TopicLabeller> labellers,
        @NonNull Duration spent, boolean stopped) {

    static final ArrivalSteps OFF = ArrivalSteps.builder().on(false).read(Set.of()).labellers(Map.of())
            .spent(Duration.ZERO).stopped(false).build();

    static final ArrivalSteps STARTED = OFF.toBuilder().on(true).build();

    ArrivalSteps spend(@NonNull Duration took) {
        return toBuilder().spent(spent.plus(took)).build();
    }
}
