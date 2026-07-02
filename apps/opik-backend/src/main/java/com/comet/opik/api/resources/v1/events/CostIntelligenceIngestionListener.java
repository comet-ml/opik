package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.SpanUpdate;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.events.SpanCostIntelligenceChanged;
import com.comet.opik.api.events.SpansCreated;
import com.comet.opik.api.events.TraceCostIntelligenceChanged;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.domain.CipxMetadata;
import com.comet.opik.domain.CipxSpendDAO;
import com.comet.opik.domain.CipxSpendDAO.SpanRow;
import com.comet.opik.domain.CipxTraceIdentityDAO;
import com.comet.opik.domain.CipxTraceIdentityDAO.TraceIdentityRow;
import com.comet.opik.domain.SpanDAO;
import com.comet.opik.domain.TraceDAO;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

import java.util.List;

/**
 * Keeps the cipx_spends / cipx_trace_identities tables in sync as spans and traces are written. Create
 * reuses the existing SpansCreated/TracesCreated (which carry the full entities); update consumes the
 * dedicated *CostIntelligenceChanged events (the *Updated events carry neither ids nor metadata). cipx
 * spans/traces are filtered out here in Java before any DB work, and the cipx fields are constructed
 * in Java. Runs on the AsyncEventBus virtual threads, off the request path; failures are logged and
 * swallowed — ingestion of the source span/trace already succeeded.
 */
@EagerSingleton
@Slf4j
public class CostIntelligenceIngestionListener {

    private final CipxSpendDAO cipxSpendDAO;
    private final CipxTraceIdentityDAO cipxTraceIdentityDAO;
    private final SpanDAO spanDAO;
    private final TraceDAO traceDAO;

    @Inject
    public CostIntelligenceIngestionListener(CipxSpendDAO cipxSpendDAO, CipxTraceIdentityDAO cipxTraceIdentityDAO,
            SpanDAO spanDAO, TraceDAO traceDAO) {
        this.cipxSpendDAO = cipxSpendDAO;
        this.cipxTraceIdentityDAO = cipxTraceIdentityDAO;
        this.spanDAO = spanDAO;
        this.traceDAO = traceDAO;
    }

    @Subscribe
    public void onSpansCreated(SpansCreated event) {
        List<SpanRow> rows = event.spans().stream()
                .filter(span -> CipxMetadata.hasSpendCall(span.metadata()))
                .map(span -> SpanRow.from(span.id(), span.traceId(), span.projectId(), span.metadata(),
                        span.startTime()))
                .toList();
        upsertSpans(rows, event.workspaceId(), event.userName());
    }

    @Subscribe
    public void onSpanCostIntelligenceChanged(SpanCostIntelligenceChanged event) {
        SpanUpdate update = event.spanUpdate();
        if (!CipxMetadata.hasSpendCall(update.metadata())) {
            return;
        }
        // project_id, trace_id and start_time are all part of / stored on the cipx_spends row but the span update
        // carries none of them per span (a batch reuses one SpanUpdate for spans that may span different traces,
        // matched by id + workspace), so resolve span -> (project, trace, start_time) from the persisted spans off
        // the request path. start_time comes from the stored span, not the UUIDv7 timestamp.
        spanDAO.getSpanRefsBySpanIds(event.spanIds(), event.workspaceId())
                .subscribe(
                        refs -> {
                            List<SpanRow> rows = event.spanIds().stream()
                                    .filter(refs::containsKey)
                                    .map(spanId -> {
                                        var ref = refs.get(spanId);
                                        return SpanRow.from(spanId, ref.traceId(), ref.projectId(),
                                                update.metadata(), ref.startTime());
                                    })
                                    .toList();
                            upsertSpans(rows, event.workspaceId(), event.userName());
                        },
                        error -> log.error("Failed to resolve span refs for cipx spend in workspace: '{}'",
                                event.workspaceId(), error));
    }

    @Subscribe
    public void onTracesCreated(TracesCreated event) {
        List<TraceIdentityRow> rows = event.traces().stream()
                .filter(trace -> CipxMetadata.hasIdentity(trace.metadata()))
                .map(trace -> TraceIdentityRow.from(trace.id(), trace.projectId(), trace.metadata(),
                        trace.startTime()))
                .toList();
        upsertTraces(rows, event.workspaceId(), event.userName());
    }

    @Subscribe
    public void onTraceCostIntelligenceChanged(TraceCostIntelligenceChanged event) {
        TraceUpdate update = event.traceUpdate();
        if (!CipxMetadata.hasIdentity(update.metadata())) {
            return;
        }
        // The event carries the resolved project_id per trace, but start_time must come from the stored trace
        // (not the UUIDv7 timestamp) so a cipx update doesn't rewrite it for backfilled/imported traces.
        var traceProjectIds = event.traceProjectIds();
        traceDAO.getStartTimesByTraceIds(traceProjectIds.keySet(), event.workspaceId())
                .subscribe(
                        startTimes -> {
                            List<TraceIdentityRow> rows = traceProjectIds.entrySet().stream()
                                    .filter(entry -> startTimes.containsKey(entry.getKey()))
                                    .map(entry -> TraceIdentityRow.from(entry.getKey(), entry.getValue(),
                                            update.metadata(), startTimes.get(entry.getKey())))
                                    .toList();
                            upsertTraces(rows, event.workspaceId(), event.userName());
                        },
                        error -> log.error("Failed to resolve start_times for cipx trace identity in workspace: '{}'",
                                event.workspaceId(), error));
    }

    private void upsertSpans(List<SpanRow> rows, String workspaceId, String userName) {
        // project_id is part of the cipx_spends merge key, so a row without it can't land correctly.
        List<SpanRow> withProject = rows.stream()
                .filter(row -> !row.projectId().isEmpty())
                .toList();
        if (withProject.isEmpty()) {
            return;
        }
        cipxSpendDAO.upsert(withProject, workspaceId, userName)
                .subscribe(
                        null,
                        error -> log.error("Failed to ingest cipx spend for workspace: '{}'", workspaceId, error),
                        () -> log.info("Ingested cipx spend for '{}' spans in workspace: '{}'", withProject.size(),
                                workspaceId));
    }

    private void upsertTraces(List<TraceIdentityRow> rows, String workspaceId, String userName) {
        List<TraceIdentityRow> withProject = rows.stream()
                .filter(row -> !row.projectId().isEmpty())
                .toList();
        if (withProject.isEmpty()) {
            return;
        }
        cipxTraceIdentityDAO.upsert(withProject, workspaceId, userName)
                .subscribe(
                        null,
                        error -> log.error("Failed to ingest cipx trace identity for workspace: '{}'", workspaceId,
                                error),
                        () -> log.info("Ingested cipx trace identity for '{}' traces in workspace: '{}'",
                                withProject.size(), workspaceId));
    }
}
