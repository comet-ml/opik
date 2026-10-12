package com.comet.opik.api.resources.utils.traces;

import com.comet.opik.api.Source;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.filter.TraceThreadField;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import lombok.experimental.UtilityClass;
import org.junit.jupiter.params.provider.Arguments;
import reactor.core.publisher.Mono;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;

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

    public static Stream<Arguments> perTraceChips() {
        return Stream.concat(
                Arrays.stream(Source.values())
                        .filter(source -> source != Source.SDK)
                        .map(otherSource -> Arguments.of(TraceThreadField.SOURCE, Source.SDK.getValue(),
                                otherSource.getValue())),
                Stream.of(Arguments.of(TraceThreadField.ENVIRONMENT, "production", "staging")));
    }

    public static Trace withPerTraceChip(Trace trace, TraceThreadField field, String value) {
        return switch (field) {
            case SOURCE -> trace.toBuilder().source(Source.fromString(value).orElseThrow()).build();
            case ENVIRONMENT -> trace.toBuilder().environment(value).build();
            default -> throw new IllegalArgumentException("not a per-trace chip: " + field);
        };
    }

    public static void deleteThreadRow(TransactionTemplateAsync clickHouseTemplate, String workspaceId,
            UUID projectId, String threadId) {
        clickHouseTemplate.nonTransaction(connection -> Mono.from(connection.createStatement("""
                DELETE FROM trace_threads
                WHERE workspace_id = :workspace_id AND project_id = :project_id AND thread_id = :thread_id
                """)
                .bind("workspace_id", workspaceId)
                .bind("project_id", projectId.toString())
                .bind("thread_id", threadId)
                .execute())
                .flatMap(result -> Mono.from(result.getRowsUpdated())))
                .block();
    }
}
