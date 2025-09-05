package com.comet.opik.domain.utils;

import lombok.experimental.UtilityClass;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@UtilityClass
public class DemoDataExclusionUtils {

    /**
     * Calculates the demo data created at timestamp by finding the maximum creation time
     * from the excluded project IDs and adding 1 minute to ensure all demo data is excluded.
     *
     * @param excludedProjectIds map of project ID to creation timestamp
     * @return Optional containing the calculated timestamp, or empty if no projects exist
     */
    public static Optional<Instant> calculateDemoDataCreatedAt(Map<UUID, Instant> excludedProjectIds) {
        return excludedProjectIds.values()
                .stream()
                .max(Comparator.naturalOrder())
                .map(createAt -> createAt.plus(1, ChronoUnit.MINUTES));
    }
}
