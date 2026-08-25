package com.comet.opik.domain;

import com.comet.opik.utils.ClickHouseDateTimeFormat;
import com.comet.opik.utils.template.TemplateUtils;
import com.fasterxml.jackson.databind.JsonNode;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.infrastructure.FilterUtils.getSTWithLogComment;
import static com.comet.opik.utils.template.TemplateUtils.getQueryItemPlaceHolder;

/**
 * Writes the cipx_trace_identities table from cipx traces. Triggered asynchronously off trace
 * create/update events (identity can arrive or change on a trace update); never reads the traces or
 * cipx_trace_identities tables. Identity fields are parsed from metadata in Java
 * ({@link TraceIdentityRow#from}). Plain INSERT relying on ReplacingMergeTree to merge by sorting
 * key. last_updated_at — the engine's version column — is bound from the source event's publish time
 * rather than left to the column DEFAULT now64(6): a trace can be upserted more than once and those
 * inserts race in the async queue, so an ingestion-time version would let a delayed older snapshot
 * overwrite a newer one. project_id must be non-empty, so blank rows are dropped.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class CipxTraceIdentityDAO {

    /** A cipx_trace_identities row constructed from a trace's metadata. */
    @Builder(toBuilder = true)
    public record TraceIdentityRow(
            @NonNull String traceId,
            @NonNull String projectId,
            @NonNull Instant startTime,
            @NonNull String userUuid,
            @NonNull String userEmail,
            @NonNull String userDisplayName,
            @NonNull String repository,
            @NonNull String sessionId,
            @NonNull String harness,
            int schemaVersion,
            @NonNull String billingMode,
            @NonNull String plan,
            @NonNull String planUsageStatus,
            @NonNull String organizationType,
            @NonNull String seatTier,
            @NonNull String billingType,
            @NonNull String branch,
            @NonNull String headShaStart,
            @NonNull String headShaEnd,
            boolean dirty,
            long commitsInTrace,
            long filesAdded,
            long filesDeleted,
            long linesAdded,
            long linesDeleted,
            long agentsDispatched,
            long agentsLinked,
            long agentsAmbiguous,
            @NonNull String cipxVersion,
            // ReplacingMergeTree version: the publish time of the event that produced this snapshot.
            @NonNull Instant lastUpdatedAt) {

        public static TraceIdentityRow from(UUID traceId, UUID projectId, JsonNode metadata, Instant startTime,
                Instant lastUpdatedAt) {
            JsonNode session = metadata.path("cipx").path("session");
            JsonNode identity = session.path("identity");
            JsonNode repository = session.path("repository");
            String userUuid = identity.path("user_uuid").asText("");
            if (userUuid.isEmpty()) {
                userUuid = identity.path("user_id").asText("");
            }
            return TraceIdentityRow.builder()
                    .traceId(traceId.toString())
                    .projectId(projectId != null ? projectId.toString() : "")
                    .startTime(startTime)
                    .userUuid(userUuid)
                    .userEmail(identity.path("email").asText(""))
                    .userDisplayName(identity.path("display_name").asText(""))
                    .repository(repository.path("remote").asText(""))
                    .sessionId(session.path("session_id").asText(""))
                    .harness(session.path("harness").asText(""))
                    .schemaVersion(session.path("schema_version").asInt(0))
                    .billingMode(identity.path("billing_mode").asText(""))
                    .plan(identity.path("plan").asText(""))
                    .planUsageStatus(identity.path("plan_usage_status").asText(""))
                    .organizationType(identity.path("organization_type").asText(""))
                    .seatTier(identity.path("seat_tier").asText(""))
                    .billingType(identity.path("billing_type").asText(""))
                    .branch(repository.path("branch").asText(""))
                    .headShaStart(repository.path("head_sha").asText(""))
                    .headShaEnd(repository.path("head_sha_end").asText(""))
                    .dirty(repository.path("dirty").asBoolean(false))
                    .commitsInTrace(asUInt32(repository.path("commits_in_trace"), "commits_in_trace"))
                    .filesAdded(asUInt32(repository.path("files_added"), "files_added"))
                    .filesDeleted(asUInt32(repository.path("files_deleted"), "files_deleted"))
                    .linesAdded(asUInt32(repository.path("lines_added"), "lines_added"))
                    .linesDeleted(asUInt32(repository.path("lines_deleted"), "lines_deleted"))
                    // Session-grain subagent link rollup. These counters are
                    // the only place cipx's worst attribution failure is
                    // visible: a subagent whose dispatch was never observed
                    // looks exactly like a main-loop turn, so no span carries
                    // a link_failure_reason and only a missing increment here
                    // reveals it. They are session RUNNING TOTALS re-stamped
                    // on every trace upsert of that session, so a session
                    // with N traces leaves N rows holding N successive
                    // snapshots. Readers must take max() per session_id,
                    // never sum().
                    .agentsDispatched(asUInt32(session.path("agents_dispatched"), "agents_dispatched"))
                    .agentsLinked(asUInt32(session.path("agents_linked"), "agents_linked"))
                    .agentsAmbiguous(asUInt32(session.path("agents_ambiguous"), "agents_ambiguous"))
                    .cipxVersion(session.path("cipx_version").asText(""))
                    .lastUpdatedAt(lastUpdatedAt)
                    .build();
        }

        // UInt32 columns, and ClickHouse wraps an out-of-range literal mod 2^32 rather than
        // rejecting it, so every way of getting this wrong is silent: asInt() makes 4294967295 a
        // negative count in Java that only round-trips by accident, and anything past 2^32 wraps
        // into a small plausible-looking one. Read the full range and clamp what is outside it.
        // A UInt32 metric off the wire. Absent reads as 0 — that is the documented
        // meaning, and a daemon that dispatched nothing is a real zero.
        //
        // Anything PRESENT that cannot be one of these counters is 0 plus a warning,
        // never a clamp. ClickHouse takes a UInt32 modulo 2^32 without complaint
        // (measured: -1 stores as 4294967295, 9999999999 as 1410065407), so the old
        // narrowing corrupted silently — but saturating at the ceiling instead is the
        // same failure wearing a different hat: 4294967295 is a legitimate value, so a
        // garbage payload would arrive indistinguishable from a real count, and these
        // feed an SLO whose entire purpose is telling "we failed" from "we correctly
        // refused". Zero degrades toward "nothing to report"; the ceiling invents the
        // largest possible claim.
        //
        // The clamp is unnecessary as well as harmful: a value in [2^31, 2^32) is a
        // legitimate UInt32 that asLong carries exactly, so the only inputs a clamp
        // ever touched were already malformed.
        private static long asUInt32(JsonNode value, String field) {
            if (value.isMissingNode() || value.isNull()) {
                return 0L;
            }
            if (!value.canConvertToLong()) {
                log.warn("cipx session metric '{}' is not a number ({}); recording 0", field, value.getNodeType());
                return 0L;
            }
            long raw = value.asLong();
            if (raw < 0L || raw > 0xFFFF_FFFFL) {
                log.warn("cipx session metric '{}' is outside UInt32 ({}); recording 0", field, raw);
                return 0L;
            }
            return raw;
        }
    }

    // One tuple per row (mirrors SpanDAO.BULK_INSERT). start_time is bound from Java (the source
    // trace's stored start).
    private static final String INSERT = """
            INSERT INTO cipx_trace_identities
                (workspace_id, project_id, trace_id, start_time, user_uuid,
                 user_email, user_display_name, repository, session_id, harness, schema_version,
                 billing_mode, plan, plan_usage_status, organization_type, seat_tier, billing_type,
                 branch, head_sha_start, head_sha_end, dirty, commits_in_trace,
                 files_added, files_deleted, lines_added, lines_deleted,
                 agents_dispatched, agents_linked, agents_ambiguous, cipx_version, last_updated_at)
            SETTINGS log_comment = '<log_comment>'
            FORMAT Values
                <items:{item |
                    (
                        :workspace_id,
                        :project_id<item.index>,
                        :trace_id<item.index>,
                        :start_time<item.index>,
                        :user_uuid<item.index>,
                        :user_email<item.index>,
                        :user_display_name<item.index>,
                        :repository<item.index>,
                        :session_id<item.index>,
                        :harness<item.index>,
                        :schema_version<item.index>,
                        :billing_mode<item.index>,
                        :plan<item.index>,
                        :plan_usage_status<item.index>,
                        :organization_type<item.index>,
                        :seat_tier<item.index>,
                        :billing_type<item.index>,
                        :branch<item.index>,
                        :head_sha_start<item.index>,
                        :head_sha_end<item.index>,
                        :dirty<item.index>,
                        :commits_in_trace<item.index>,
                        :files_added<item.index>,
                        :files_deleted<item.index>,
                        :lines_added<item.index>,
                        :lines_deleted<item.index>,
                        :agents_dispatched<item.index>,
                        :agents_linked<item.index>,
                        :agents_ambiguous<item.index>,
                        :cipx_version<item.index>,
                        :last_updated_at<item.index>
                    )
                    <if(item.hasNext)>,<endif>
                }>
            ;
            """;

    private final @NonNull ConnectionFactory connectionFactory;

    public Mono<Long> upsert(@NonNull List<TraceIdentityRow> rows, @NonNull String workspaceId,
            @NonNull String userName) {
        if (rows.isEmpty()) {
            return Mono.just(0L);
        }
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> insert(rows, workspaceId, userName, connection))
                .flatMap(Result::getRowsUpdated)
                .reduce(0L, Long::sum);
    }

    private Publisher<? extends Result> insert(List<TraceIdentityRow> rows, String workspaceId, String userName,
            Connection connection) {
        List<TemplateUtils.QueryItem> queryItems = getQueryItemPlaceHolder(rows.size());
        ST template = getSTWithLogComment(INSERT, "insert_cipx_trace_identities", workspaceId, userName, rows.size());
        template.add("items", queryItems);
        Statement statement = connection.createStatement(template.render());

        // Positional binds: the driver resolves named binds with a linear indexOf over the statement's
        // parameter list (quadratic per statement), while bind(int) is a direct array write. Indices
        // follow the placeholders' first-appearance order in the rendered SQL: workspace_id once at 0
        // (repeats dedup), then 30 parameters per row tuple in template order. The bind order below
        // must stay in lockstep with the INSERT tuple above — nothing checks it at compile time, and
        // a mismatch silently writes each value into the neighbouring column.
        statement.bind(0, workspaceId);
        int index = 1;
        for (TraceIdentityRow row : rows) {
            statement.bind(index++, row.projectId())
                    .bind(index++, row.traceId())
                    .bind(index++, ClickHouseDateTimeFormat.formatNanos(row.startTime()))
                    .bind(index++, row.userUuid())
                    .bind(index++, row.userEmail())
                    .bind(index++, row.userDisplayName())
                    .bind(index++, row.repository())
                    .bind(index++, row.sessionId())
                    .bind(index++, row.harness())
                    .bind(index++, row.schemaVersion())
                    .bind(index++, row.billingMode())
                    .bind(index++, row.plan())
                    .bind(index++, row.planUsageStatus())
                    .bind(index++, row.organizationType())
                    .bind(index++, row.seatTier())
                    .bind(index++, row.billingType())
                    .bind(index++, row.branch())
                    .bind(index++, row.headShaStart())
                    .bind(index++, row.headShaEnd())
                    .bind(index++, row.dirty())
                    .bind(index++, row.commitsInTrace())
                    .bind(index++, row.filesAdded())
                    .bind(index++, row.filesDeleted())
                    .bind(index++, row.linesAdded())
                    .bind(index++, row.linesDeleted())
                    .bind(index++, row.agentsDispatched())
                    .bind(index++, row.agentsLinked())
                    .bind(index++, row.agentsAmbiguous())
                    .bind(index++, row.cipxVersion())
                    .bind(index++, ClickHouseDateTimeFormat.formatMicros(row.lastUpdatedAt()));
        }

        return statement.execute();
    }
}
