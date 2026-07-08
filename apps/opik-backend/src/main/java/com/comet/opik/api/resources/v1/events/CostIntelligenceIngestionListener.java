package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Span;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.events.SpansCreated;
import com.comet.opik.api.events.TraceCostIntelligenceChanged;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.domain.CipxMetadata;
import com.comet.opik.domain.CipxSpendBlockDAO;
import com.comet.opik.domain.CipxSpendBlockDAO.BlockRow;
import com.comet.opik.domain.CipxSpendDAO;
import com.comet.opik.domain.CipxSpendDAO.SpanRow;
import com.comet.opik.domain.CipxTraceIdentityDAO;
import com.comet.opik.domain.CipxTraceIdentityDAO.TraceIdentityRow;
import com.comet.opik.domain.CipxUserMappingDAO;
import com.comet.opik.domain.CipxUserMappingDAO.UserMapping;
import com.comet.opik.domain.TraceDAO;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.List;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/**
 * Populates the Cost Intelligence tables as spans and traces are written: cipx_spends (span-level
 * call data) + cipx_spend_blocks (per-block rows with ingestion-derived allocation) from cipx-call
 * spans, and cipx_trace_identities (ClickHouse) + cipx_user_mappings (MySQL email -> uuid lookup)
 * from cipx traces. Span ingestion is create-only — cipx call data is complete on the create event
 * and immutable, so there is no span update path. Trace identity can arrive or change on a trace
 * update, so identity create reuses TracesCreated (which carries the full entities) and update
 * consumes the dedicated TraceCostIntelligenceChanged event (TracesUpdated carries only a delta).
 * cipx spans/traces are filtered out here in Java before any DB work, and all cipx fields are
 * derived in Java. Runs on the AsyncEventBus virtual threads, off the request path; failures are
 * logged and swallowed — ingestion of the source span/trace already succeeded.
 */
@EagerSingleton
@Slf4j
public class CostIntelligenceIngestionListener {

    private final CipxSpendDAO cipxSpendDAO;
    private final CipxSpendBlockDAO cipxSpendBlockDAO;
    private final CipxTraceIdentityDAO cipxTraceIdentityDAO;
    private final TraceDAO traceDAO;
    private final TransactionTemplate transactionTemplate;

    @Inject
    public CostIntelligenceIngestionListener(CipxSpendDAO cipxSpendDAO, CipxSpendBlockDAO cipxSpendBlockDAO,
            CipxTraceIdentityDAO cipxTraceIdentityDAO, TraceDAO traceDAO, TransactionTemplate transactionTemplate) {
        this.cipxSpendDAO = cipxSpendDAO;
        this.cipxSpendBlockDAO = cipxSpendBlockDAO;
        this.cipxTraceIdentityDAO = cipxTraceIdentityDAO;
        this.traceDAO = traceDAO;
        this.transactionTemplate = transactionTemplate;
    }

    @Subscribe
    public void onSpansCreated(SpansCreated event) {
        // project_id is part of both merge keys, so a span without it can't land correctly.
        List<Span> cipxSpans = event.spans().stream()
                .filter(span -> CipxMetadata.hasSpendCall(span.metadata()))
                .filter(span -> span.projectId() != null)
                .toList();

        if (cipxSpans.isEmpty()) {
            return;
        }

        log.info("cipx onSpansCreated on thread '{}': '{}' spans in event, '{}' cipx, workspace: '{}'",
                Thread.currentThread(), event.spans().size(), cipxSpans.size(), event.workspaceId());

        List<SpanRow> spanRows = cipxSpans.stream()
                .map(span -> SpanRow.from(span.id(), span.traceId(), span.projectId(), span.metadata(),
                        span.startTime()))
                .toList();
        cipxSpendDAO.insert(spanRows, event.workspaceId(), event.userName())
                .doOnSubscribe(subscription -> log.info(
                        "cipx spend insert subscribed for '{}' rows in workspace: '{}'", spanRows.size(),
                        event.workspaceId()))
                .subscribe(
                        null,
                        error -> log.error("Failed to ingest cipx spend for workspace: '{}'", event.workspaceId(),
                                error),
                        () -> log.info("Ingested cipx spend for '{}' spans in workspace: '{}'", spanRows.size(),
                                event.workspaceId()));

        List<BlockRow> blockRows = cipxSpans.stream()
                .flatMap(span -> BlockRow.from(span.id(), span.traceId(), span.projectId(), span.metadata(),
                        span.startTime()).stream())
                .toList();
        cipxSpendBlockDAO.insert(blockRows, event.workspaceId(), event.userName())
                .doOnSubscribe(subscription -> log.info(
                        "cipx spend blocks insert subscribed for '{}' rows in workspace: '{}'", blockRows.size(),
                        event.workspaceId()))
                .subscribe(
                        null,
                        error -> log.error("Failed to ingest cipx spend blocks for workspace: '{}'",
                                event.workspaceId(), error),
                        () -> log.info("Ingested '{}' cipx spend blocks for '{}' spans in workspace: '{}'",
                                blockRows.size(), cipxSpans.size(), event.workspaceId()));
    }

    @Subscribe
    public void onTracesCreated(TracesCreated event) {
        List<TraceIdentityRow> rows = event.traces().stream()
                .filter(trace -> CipxMetadata.hasIdentity(trace.metadata()))
                .map(trace -> TraceIdentityRow.from(trace.id(), trace.projectId(), trace.metadata(),
                        trace.startTime()))
                .toList();
        ingestIdentities(rows, event.workspaceId(), event.userName());
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
        // ingestIdentities ends with a blocking MySQL write; publishOn moves the callback off the
        // ClickHouse driver's I/O thread so that write can't stall unrelated ClickHouse queries.
        traceDAO.getStartTimesByTraceIds(traceProjectIds.keySet(), event.workspaceId())
                .publishOn(Schedulers.boundedElastic())
                .subscribe(
                        startTimes -> {
                            List<TraceIdentityRow> rows = traceProjectIds.entrySet().stream()
                                    .filter(entry -> startTimes.containsKey(entry.getKey()))
                                    .map(entry -> TraceIdentityRow.from(entry.getKey(), entry.getValue(),
                                            update.metadata(), startTimes.get(entry.getKey())))
                                    .toList();
                            ingestIdentities(rows, event.workspaceId(), event.userName());
                        },
                        error -> log.error("Failed to resolve start_times for cipx trace identity in workspace: '{}'",
                                event.workspaceId(), error));
    }

    private void ingestIdentities(List<TraceIdentityRow> rows, String workspaceId, String userName) {
        // project_id is part of the cipx_trace_identities merge key, so a row without it can't land correctly.
        List<TraceIdentityRow> withProject = rows.stream()
                .filter(row -> !row.projectId().isEmpty())
                .toList();
        if (withProject.isEmpty()) {
            return;
        }

        cipxTraceIdentityDAO.upsert(withProject, workspaceId, userName)
                .doOnSubscribe(subscription -> log.info(
                        "cipx trace identity upsert subscribed for '{}' rows in workspace: '{}'", withProject.size(),
                        workspaceId))
                .subscribe(
                        null,
                        error -> log.error("Failed to ingest cipx trace identity for workspace: '{}'", workspaceId,
                                error),
                        () -> log.info("Ingested cipx trace identity for '{}' traces in workspace: '{}'",
                                withProject.size(), workspaceId));

        List<UserMapping> mappings = withProject.stream()
                .filter(row -> !row.userEmail().isEmpty() && !row.userUuid().isEmpty())
                .map(row -> UserMapping.builder().userEmail(row.userEmail()).userUuid(row.userUuid()).build())
                .distinct()
                .toList();
        if (!mappings.isEmpty()) {
            try {
                transactionTemplate.inTransaction(WRITE, handle -> {
                    handle.attach(CipxUserMappingDAO.class).save(mappings);
                    return null;
                });
            } catch (Exception error) {
                log.error("Failed to ingest cipx user mappings for workspace: '{}'", workspaceId, error);
            }
        }
    }
}
