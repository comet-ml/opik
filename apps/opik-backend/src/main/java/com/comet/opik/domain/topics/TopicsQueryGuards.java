package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.stringtemplate.v4.ST;

/** The ClickHouse guards of a read over a project's window: validated config numbers, never input. */
@UtilityClass
class TopicsQueryGuards {

    /** The vectors job's window read guards ({@code topics.vectors.find}), and the time limit. */
    static ST windowRead(@NonNull ST template, @NonNull TopicsConfig config, long maxExecutionSeconds) {
        var find = config.getVectors().getFind();
        return template.add("max_rows_to_read", find.getMaxRowsToRead())
                .add("max_memory_usage", find.getMaxMemoryUsage())
                .add("max_execution_time", maxExecutionSeconds);
    }
}
