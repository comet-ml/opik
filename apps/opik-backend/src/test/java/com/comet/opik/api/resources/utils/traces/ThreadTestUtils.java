package com.comet.opik.api.resources.utils.traces;

import com.comet.opik.api.Source;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.domain.IdGenerator;
import lombok.experimental.UtilityClass;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@UtilityClass
public class ThreadTestUtils {

    public static Trace buildThreadTrace(PodamFactory factory, IdGenerator idGenerator, String projectName,
            String threadId, Instant ranAt, long durationMs) {
        return factory.manufacturePojo(Trace.class).toBuilder()
                .id(idGenerator.generateId(ranAt))
                .projectName(projectName)
                .threadId(threadId)
                .startTime(ranAt)
                .endTime(ranAt.plus(durationMs, ChronoUnit.MILLIS))
                .errorInfo(null)
                .build();
    }

    public static Trace buildThreadTrace(PodamFactory factory, IdGenerator idGenerator, String projectName,
            String threadId, Source source, Instant ranAt, long durationMs) {
        return buildThreadTrace(factory, idGenerator, projectName, threadId, ranAt, durationMs).toBuilder()
                .source(source)
                .build();
    }

    public static Span buildCostedSpan(PodamFactory factory, IdGenerator idGenerator, String projectName,
            Trace trace, BigDecimal cost) {
        return factory.manufacturePojo(Span.class).toBuilder()
                .id(idGenerator.generateId(trace.startTime().plus(1, ChronoUnit.MILLIS)))
                .traceId(trace.id())
                .projectName(projectName)
                .startTime(trace.startTime())
                .endTime(trace.endTime())
                .totalEstimatedCost(cost)
                .errorInfo(null)
                .build();
    }
}
