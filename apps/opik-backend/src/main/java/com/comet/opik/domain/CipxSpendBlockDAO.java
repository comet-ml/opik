package com.comet.opik.domain;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.client.api.metrics.ServerMetrics;
import com.clickhouse.data.ClickHouseFormat;
import com.comet.opik.utils.ClickHouseDateTimeFormat;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.collect.Lists;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Writes the cipx_spend_blocks table: one row per cipx block, with the token allocation and dashboard
 * groupings derived in Java at ingestion time ({@link BlockRow#from}) so retrieval never re-derives
 * them. Two kinds of rows, discriminated by {@code src}: 'a' (attributed, one per real block) and
 * 'r' (residual — billed tiers with no block to absorb them, surfaced as 'unattributed').
 *
 * <p>The allocation splits each of the span's four billed usage counters across the span's blocks of
 * the matching tier, proportionally to chars: {@code alloc = chars * u_tier / tier_chars}. Every input
 * comes from the single span payload, so the whole derivation is one pass over the span's blocks.
 *
 * <p>block_idx keeps the ReplacingMergeTree sorting key unique per row and is deterministic (raw
 * position in cipx.blocks[] for attributed rows, 65531 + tier ordinal for residual rows) so a replayed
 * insert produces identical keys and dedups instead of duplicating. Plain INSERT, same contract as
 * {@link CipxSpendDAO}: create-only ingestion, project_id must be non-empty, last_updated_at is left
 * to the column DEFAULT now64(6).
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class CipxSpendBlockDAO {

    /** Tier names ordered by the residual block_idx ordinal. */
    private static final String[] TIER_NAMES = {"input", "cache_read", "cache_creation", "output"};
    private static final int RESIDUAL_IDX_BASE = 65531;
    /** Rows per bulk-insert chunk; caps the JSON payload at ~35MB (~650 bytes/row). */
    private static final int INSERT_CHUNK_SIZE = 50_000;
    private static final String SRC_ATTRIBUTED = "a";
    private static final String SRC_RESIDUAL = "r";

    /** A cipx_spend_blocks row: raw block fields plus the ingestion-derived columns. */
    @Builder(toBuilder = true)
    public record BlockRow(
            @NonNull String spanId,
            @NonNull String traceId,
            @NonNull String projectId,
            @NonNull Instant startTime,
            @NonNull String model,
            int blockIdx,
            @NonNull String src,
            @NonNull String category,
            @NonNull String side,
            @NonNull String cacheStatus,
            @NonNull String parentCategory,
            long chars,
            @NonNull String toolName,
            @NonNull String toolServer,
            @NonNull String toolUseId,
            @NonNull String resource,
            @NonNull String kind,
            @NonNull String tier,
            @NonNull String lane,
            @NonNull String bdLane,
            @NonNull String label,
            int isDefinition,
            double alloc) {

        /**
         * Derives all rows for one cipx span: one attributed row per non-identity block (keeping the
         * block's raw array position as block_idx) plus a residual row for each tier billed on the
         * span with no block in it. Parity rules with the pre-derivation queries: a tier whose blocks
         * sum to zero chars allocates 0 and produces NO residual (the tier is present); blocks whose
         * (side, cache_status) maps to no tier still get rows with alloc 0 (breakdowns count them).
         */
        public static List<BlockRow> from(UUID spanId, UUID traceId, UUID projectId, JsonNode metadata,
                Instant startTime) {
            JsonNode call = metadata.path("cipx").path("call");
            JsonNode usage = call.path("usage");
            String model = call.path("model").asText("");
            long[] tierTokens = {
                    usage.path("input_tokens").asLong(0),
                    usage.path("cache_read_input_tokens").asLong(0),
                    usage.path("cache_creation_input_tokens").asLong(0),
                    usage.path("output_tokens").asLong(0),
            };

            JsonNode blocks = metadata.path("cipx").path("blocks");
            long[] tierChars = new long[TIER_NAMES.length];
            boolean[] tierPresent = new boolean[TIER_NAMES.length];
            if (blocks.isArray()) {
                for (JsonNode block : blocks) {
                    if (isIdentityContext(block)) {
                        continue;
                    }
                    int tier = tierOrdinal(block.path("side").asText(""), block.path("cache_status").asText(""));
                    if (tier >= 0) {
                        tierChars[tier] += block.path("chars").asLong(0);
                        tierPresent[tier] = true;
                    }
                }
            }

            List<BlockRow> rows = new ArrayList<>();
            var base = BlockRow.builder()
                    .spanId(spanId.toString())
                    .traceId(traceId.toString())
                    .projectId(projectId != null ? projectId.toString() : "")
                    .startTime(startTime)
                    .model(model);
            if (blocks.isArray()) {
                for (int idx = 0; idx < blocks.size(); idx++) {
                    JsonNode block = blocks.get(idx);
                    if (isIdentityContext(block)) {
                        continue;
                    }
                    rows.add(attributed(base, idx, block, tierTokens, tierChars));
                }
            }
            for (int tier = 0; tier < TIER_NAMES.length; tier++) {
                if (!tierPresent[tier] && tierTokens[tier] > 0) {
                    rows.add(residual(base, tier, tierTokens[tier]));
                }
            }
            return rows;
        }

        private static BlockRow attributed(BlockRowBuilder base, int idx, JsonNode block, long[] tierTokens,
                long[] tierChars) {
            String category = block.path("category").asText("");
            String side = block.path("side").asText("");
            String cacheStatus = block.path("cache_status").asText("");
            String toolName = block.path("tool_name").asText("");
            String toolServer = block.path("tool_server").asText("");
            String resource = block.path("resource").asText("");
            String kind = block.path("kind").asText("");
            long chars = block.path("chars").asLong(0);

            int tier = tierOrdinal(side, cacheStatus);
            double alloc = tier >= 0 && tierChars[tier] > 0
                    ? chars * (double) tierTokens[tier] / tierChars[tier]
                    : 0;
            return base
                    .blockIdx(idx)
                    .src(SRC_ATTRIBUTED)
                    .category(category)
                    .side(side)
                    .cacheStatus(cacheStatus)
                    .parentCategory(block.path("parent_category").asText(""))
                    .chars(chars)
                    .toolName(toolName)
                    .toolServer(toolServer)
                    .toolUseId(block.path("tool_use_id").asText(""))
                    .resource(resource)
                    .kind(kind)
                    .tier(tier >= 0 ? TIER_NAMES[tier] : "")
                    .lane(lane(category, toolServer))
                    .bdLane(bdLane(category, toolServer))
                    .label(label(category, toolServer, toolName, resource, kind, chars))
                    .isDefinition(isDefinition(category))
                    .alloc(alloc)
                    .build();
        }

        private static BlockRow residual(BlockRowBuilder base, int tier, long tokens) {
            return base
                    .blockIdx(RESIDUAL_IDX_BASE + tier)
                    .src(SRC_RESIDUAL)
                    .category("")
                    .side("")
                    .cacheStatus("")
                    .parentCategory("")
                    .chars(0)
                    .toolName("")
                    .toolServer("")
                    .toolUseId("")
                    .resource("")
                    .kind("")
                    .tier(TIER_NAMES[tier])
                    .lane("unattributed")
                    .bdLane("")
                    .label("")
                    .isDefinition(0)
                    .alloc(tokens)
                    .build();
        }

        private static boolean isIdentityContext(JsonNode block) {
            return "identity_context".equals(block.path("category").asText())
                    && "identity_context".equals(block.path("parent_category").asText());
        }

        private static int tierOrdinal(String side, String cacheStatus) {
            if ("output".equals(side)) {
                return 3;
            }
            if ("input".equals(side)) {
                return switch (cacheStatus) {
                    case "fresh" -> 0;
                    case "read" -> 1;
                    case "write" -> 2;
                    default -> -1;
                };
            }
            return -1;
        }

        /** Composition lane: every category maps somewhere; unknown categories fall to 'unattributed'. */
        private static String lane(String category, String toolServer) {
            return switch (category) {
                case "tool_io" -> toolServer.isEmpty() ? "built_in_tools" : "mcp_servers";
                case "user_prompts" -> "user_prompts";
                case "prior_assistant" -> "prior_assistant";
                case "mcp_tools_active", "mcp_tools_deferred", "mcp_server_instructions" -> "mcp_servers";
                case "skills_menu", "skills_loaded" -> "skills";
                case "custom_agents" -> "custom_agents";
                case "memory" -> "memory";
                case "file_attachments" -> "file_attachments";
                case "system_prompt", "env_info", "system_tools", "system_tools_deferred" -> "static_overhead";
                case "thinking" -> "thinking";
                case "assistant_text" -> "assistant_text";
                case "built_in_tool_calls" -> "built_in_tool_calls";
                case "mcp_tool_calls" -> "mcp_tool_calls";
                case "skill_invocations" -> "skill_invocations";
                default -> "unattributed";
            };
        }

        /** Breakdown lane: like {@link #lane} but categories with no breakdown rows map to '' (excluded). */
        private static String bdLane(String category, String toolServer) {
            return switch (category) {
                case "tool_io" -> toolServer.isEmpty() ? "built_in_tools" : "mcp_servers";
                case "user_prompts" -> "user_prompts";
                case "prior_assistant" -> "prior_assistant";
                case "mcp_tools_active" -> "mcp_servers";
                case "skills_menu", "skills_loaded" -> "skills";
                case "custom_agents" -> "custom_agents";
                case "memory" -> "memory";
                case "file_attachments" -> "file_attachments";
                case "system_prompt", "env_info", "system_tools", "system_tools_deferred" -> "static_overhead";
                case "thinking" -> "thinking";
                case "assistant_text" -> "assistant_text";
                case "built_in_tool_calls" -> "built_in_tool_calls";
                case "mcp_tool_calls" -> "mcp_tool_calls";
                case "skill_invocations" -> "skill_invocations";
                default -> "";
            };
        }

        /** Breakdown row key; which raw field names the row depends on the category. */
        private static String label(String category, String toolServer, String toolName, String resource,
                String kind, long chars) {
            return switch (category) {
                case "user_prompts" ->
                    chars < 1_000 ? "small" : chars < 10_000 ? "medium" : chars < 100_000 ? "large" : "xlarge";
                case "file_attachments", "skills_menu", "skills_loaded", "custom_agents", "memory",
                        "skill_invocations" ->
                    resource;
                case "tool_io" -> toolServer.isEmpty() ? toolName : toolServer;
                case "prior_assistant" -> kind;
                case "mcp_tools_active", "mcp_tool_calls" -> toolServer;
                case "system_prompt", "env_info", "system_tools", "system_tools_deferred" -> category;
                case "thinking" -> "thinking";
                case "assistant_text" -> "assistant_text";
                case "built_in_tool_calls" -> toolName;
                default -> "";
            };
        }

        /** 1 = cost of carrying the thing (schemas, menus, standing context); 0 = cost of using it. */
        private static int isDefinition(String category) {
            return switch (category) {
                case "skills_menu", "custom_agents", "memory", "mcp_tools_active",
                        "system_prompt", "env_info", "system_tools", "system_tools_deferred" ->
                    1;
                default -> 0;
            };
        }
    }

    private final @NonNull Client clickHouseClient;

    /**
     * Bulk insert via the ClickHouse v2 HTTP client using JSONEachRow, NOT the R2DBC statement path
     * the sibling cipx DAOs use. One span event fans out to hundreds of block rows (~350/span, so a
     * 200-span batch is ~70k rows x 24 columns), and the R2DBC driver resolves every named bind with
     * a linear scan over the statement's parameter list — O(n^2) over ~1.7M parameters, hours of CPU
     * for a single event (see ExperimentAggregatesDAO.insertExperimentItems for the same trade-off).
     * The JSONEachRow payload is one HTTP body with no per-parameter work at all.
     *
     * <p>last_updated_at is omitted from the payload so the column DEFAULT now64(6) applies
     * (input_format_defaults_for_omitted_fields is on by default). Fully non-blocking: the client is
     * built with useAsyncRequests(true) (see DatabaseAnalyticsFactory.buildClient), so the returned
     * future runs the HTTP round-trip on the v2 client's own executor — no shared scheduler
     * (boundedElastic or otherwise) is borrowed for the I/O.
     *
     * <p>Rows are inserted in sequential chunks so the peak payload allocation per event is bounded
     * by one chunk regardless of the incoming span batch size (which is client-controlled): a
     * 1000-span batch fans out to ~350k block rows, which as a single JSON body would transiently
     * allocate on the order of 1GB (UTF-16 builder + String copy + UTF-8 bytes).
     */
    public Mono<Long> insert(@NonNull List<BlockRow> rows, @NonNull String workspaceId, @NonNull String userName) {
        if (rows.isEmpty()) {
            return Mono.just(0L);
        }
        return Flux.fromIterable(Lists.partition(rows, INSERT_CHUNK_SIZE))
                .concatMap(chunk -> insertChunk(chunk, workspaceId, userName))
                .reduce(0L, Long::sum);
    }

    private Mono<Long> insertChunk(List<BlockRow> rows, String workspaceId, String userName) {
        return Mono.fromFuture(() -> {
            StringBuilder body = new StringBuilder();
            for (BlockRow row : rows) {
                appendJsonRow(body, workspaceId, row);
            }
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

            String logComment = "insert_cipx_spend_blocks:%s:%s:%d".formatted(workspaceId, userName, rows.size());
            var settings = new InsertSettings()
                    .logComment(logComment)
                    .serverSetting("date_time_input_format", "best_effort");

            return clickHouseClient.insert(
                    "cipx_spend_blocks",
                    new ByteArrayInputStream(payload),
                    ClickHouseFormat.JSONEachRow,
                    settings);
        }).map(response -> {
            try (response) {
                return response.getMetrics().getMetric(ServerMetrics.NUM_ROWS_WRITTEN).getLong();
            }
        });
    }

    private void appendJsonRow(StringBuilder out, String workspaceId, BlockRow row) {
        var node = JsonUtils.createObjectNode();
        node.put("workspace_id", workspaceId);
        node.put("project_id", row.projectId());
        node.put("trace_id", row.traceId());
        node.put("span_id", row.spanId());
        node.put("block_idx", row.blockIdx());
        node.put("model", row.model());
        node.put("src", row.src());
        node.put("category", row.category());
        node.put("side", row.side());
        node.put("cache_status", row.cacheStatus());
        node.put("parent_category", row.parentCategory());
        node.put("chars", row.chars());
        node.put("tool_name", row.toolName());
        node.put("tool_server", row.toolServer());
        node.put("tool_use_id", row.toolUseId());
        node.put("resource", row.resource());
        node.put("kind", row.kind());
        node.put("tier", row.tier());
        node.put("lane", row.lane());
        node.put("bd_lane", row.bdLane());
        node.put("label", row.label());
        node.put("is_definition", row.isDefinition());
        node.put("alloc", row.alloc());
        node.put("start_time", ClickHouseDateTimeFormat.formatNanos(row.startTime()));
        out.append(node).append('\n');
    }
}
