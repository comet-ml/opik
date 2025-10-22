package com.comet.opik.infrastructure.queues;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * Represents the job data to be executed by RQ worker.
 *
 * This is what the client provides - just the function name, args, and kwargs.
 * This data will be serialized to plain JSON and stored in the 'data' field of the RQ job.
 */
@Builder(toBuilder = true)
public record Job(
        @JsonProperty("func") String func,
        @JsonProperty("args") List<Object> args,
        @JsonProperty("kwargs") Map<String, Object> kwargs) {

    /**
     * Builder with immutable empty defaults.
     *
     * Note: Defaults use {@code List.of()} and {@code Map.of()} which are immutable.
     * Callers should use {@code toBuilder()} or supply mutable collections if mutation is required
     * before building. After build, the record should be treated as immutable.
     */
    public static class JobBuilder {
        JobBuilder() {
            this.args = List.of();
            this.kwargs = Map.of();
        }
    }
}
