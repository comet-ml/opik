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
import com.comet.opik.domain.retention.RetentionUtils;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

import java.util.List;

/**
 * Keeps the cipx_spend / cipx_trace_identity tables in sync as spans and traces are written. Create
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

    @Inject
    public CostIntelligenceIngestionListener(CipxSpendDAO cipxSpendDAO, CipxTraceIdentityDAO cipxTraceIdentityDAO) {
        this.cipxSpendDAO = cipxSpendDAO;
        this.cipxTraceIdentityDAO = cipxTraceIdentityDAO;
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
        List<SpanRow> rows = event.spanProjectIds().entrySet().stream()
                .map(entry -> SpanRow.from(entry.getKey(), update.traceId(), entry.getValue(), update.metadata(),
                        RetentionUtils.extractInstant(entry.getKey())))
                .toList();
        upsertSpans(rows, event.workspaceId(), event.userName());
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
        List<TraceIdentityRow> rows = event.traceProjectIds().entrySet().stream()
                .map(entry -> TraceIdentityRow.from(entry.getKey(), entry.getValue(), update.metadata(),
                        RetentionUtils.extractInstant(entry.getKey())))
                .toList();
        upsertTraces(rows, event.workspaceId(), event.userName());
    }

    private void upsertSpans(List<SpanRow> rows, String workspaceId, String userName) {
        // project_id is part of the cipx_spend merge key, so a row without it can't land correctly.
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
