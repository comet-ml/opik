package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.uuid.Generators;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Handle;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;

/**
 * The seeds and reads that the topics container tests share: vectors seeded into {@code topic_vectors}, the per-table
 * reads of a project's topic data, the raw tombstone rows that no production write sets yet, and opik-inference's
 * {@code /v1/generate} answer.
 */
@RequiredArgsConstructor
final class TopicsTestData {

    static final String GENERATE_PATH = "/v1/generate";

    private final @NonNull TransactionTemplate template;
    private final @NonNull TransactionTemplateAsync clickHouseTemplate;
    private final @NonNull TopicVectorsDAO vectorsDAO;
    /** The traces {@link #seed} writes. */
    private final @NonNull Traces traces;

    /**
     * Traces to seed as vectors: trace {@code i} has {@code traceIds[i]}, {@code createdAt[i]}, {@code texts[i]} and
     * the {@code i}-th {@link TopicVectorsTickService#EMBED_DIMENSIONS} floats of {@code vectors}. {@code night} is the
     * start of the day after the last trace, so the traces are the window of that night.
     */
    record Traces(List<UUID> traceIds, List<Instant> createdAt, String[] texts, float[] vectors, Instant night) {

        /** {@code count} traces of random unit vectors, ten minutes apart, the last one ten minutes before night. */
        static Traces synthetic(int count, Instant night, long seed) {
            int dimensions = TopicVectorsTickService.EMBED_DIMENSIONS;
            var random = new Random(seed);
            var traceIds = new ArrayList<UUID>(count);
            var createdAt = new ArrayList<Instant>(count);
            var texts = new String[count];
            var vectors = new float[count * dimensions];
            for (int i = 0; i < count; i++) {
                var created = night.minus(Duration.ofMinutes(10L * (count - i)));
                createdAt.add(created);
                traceIds.add(Generators.timeBasedEpochGenerator().construct(created.toEpochMilli()));
                texts[i] = "intent " + i;
                double norm = 0;
                for (int t = 0; t < dimensions; t++) {
                    var value = (float) random.nextGaussian();
                    vectors[i * dimensions + t] = value;
                    norm += value * value;
                }
                for (int t = 0; t < dimensions; t++) {
                    vectors[i * dimensions + t] /= (float) Math.sqrt(norm);
                }
            }
            return new Traces(List.copyOf(traceIds), List.copyOf(createdAt), texts, vectors, night);
        }

        /**
         * {@code count} traces of random unit vectors spread evenly over the {@code days} before night. The ids are
         * UUID v7 with tails from {@code seed}, so the same seed gives the same ids.
         */
        static Traces spread(int count, Instant night, int days, long seed) {
            int dimensions = TopicVectorsTickService.EMBED_DIMENSIONS;
            var random = new Random(seed);
            var ids = Generators.timeBasedEpochGenerator(random);
            var step = Duration.ofDays(days).dividedBy(count);
            var start = night.minus(Duration.ofDays(days));
            var traceIds = new ArrayList<UUID>(count);
            var createdAt = new ArrayList<Instant>(count);
            var texts = new String[count];
            var vectors = new float[count * dimensions];
            for (int i = 0; i < count; i++) {
                var created = start.plus(step.multipliedBy(i));
                createdAt.add(created);
                traceIds.add(ids.construct(created.toEpochMilli()));
                texts[i] = "intent " + i;
                double norm = 0;
                for (int t = 0; t < dimensions; t++) {
                    var value = (float) random.nextGaussian();
                    vectors[i * dimensions + t] = value;
                    norm += value * value;
                }
                for (int t = 0; t < dimensions; t++) {
                    vectors[i * dimensions + t] /= (float) Math.sqrt(norm);
                }
            }
            return new Traces(List.copyOf(traceIds), List.copyOf(createdAt), texts, vectors, night);
        }
    }

    /**
     * The traces {@code [from, from + count)} as the project's vectors, and the project itself in MySQL, with its
     * {@code last_updated_trace_at} at its latest seeded trace, as the trace writes keep it.
     */
    void seed(String workspaceId, UUID projectId, int from, int count) {
        seed(workspaceId, projectId, from, count, traces.night());
    }

    /**
     * As {@link #seed(String, UUID, int, int)}, with each trace's time moved from the traces' night to {@code night},
     * so the window keeps the same traces.
     */
    void seed(String workspaceId, UUID projectId, int from, int count, Instant night) {
        var shift = Duration.between(traces.night(), night);
        var lastTrace = traces.createdAt().get(from + count - 1).plus(shift);
        template.inTransaction(WRITE, handle -> handle.execute("""
                INSERT INTO projects (id, name, workspace_id, last_updated_trace_at) VALUES (?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE last_updated_trace_at = GREATEST(IFNULL(last_updated_trace_at, ?), ?)
                """, projectId.toString(), "topics-" + projectId, workspaceId, lastTrace, lastTrace, lastTrace));
        int dimensions = TopicVectorsTickService.EMBED_DIMENSIONS;
        var rows = new ArrayList<TopicVector>(count);
        for (int i = from; i < from + count; i++) {
            var vector = new ArrayList<Float>(dimensions);
            for (int t = 0; t < dimensions; t++) {
                vector.add(traces.vectors()[i * dimensions + t]);
            }
            rows.add(TopicVector.builder()
                    .workspaceId(workspaceId)
                    .projectId(projectId)
                    .traceId(traces.traceIds().get(i))
                    .traceCreatedAt(traces.createdAt().get(i).plus(shift))
                    .taskVersion(1)
                    .embedModel(TopicVectorsTickService.EMBED_MODEL)
                    .embedDimensions(dimensions)
                    .text(traces.texts()[i])
                    .vector(vector)
                    .build());
        }
        vectorsDAO.insert(rows).block();
    }

    /** The project's row in MySQL, with no trace written yet; kept as it is when it exists. */
    void project(String workspaceId, UUID projectId) {
        template.inTransaction(WRITE, handle -> handle.execute(
                "INSERT IGNORE INTO projects (id, name, workspace_id) VALUES (?, ?, ?)",
                projectId.toString(), "topics-" + projectId, workspaceId));
    }

    /**
     * What a saved run leaves in MySQL for a project: a topic of {@code size} traces, its prototype and an Other one,
     * the state, and the run, saved two hours after {@code night}.
     */
    void savedList(String workspaceId, UUID projectId, Instant night, int size) {
        var runId = UUID.randomUUID();
        var topicId = UUID.randomUUID();
        var savedAt = night.plus(Duration.ofHours(2));
        var vector = TopicPrototype.encode(new float[TopicVectorsTickService.EMBED_DIMENSIONS]);
        template.inTransaction(WRITE, handle -> {
            var topicDAO = handle.attach(TopicDAO.class);
            topicDAO.insertState(TopicProjectState.builder()
                    .projectId(projectId)
                    .workspaceId(workspaceId)
                    .rebuilds(1)
                    .firstBuiltAt(savedAt)
                    .lastRunId(runId)
                    .lastSuccessAt(savedAt)
                    .windowUntil(night)
                    .build());
            topicDAO.insertTopics(List.of(Topic.builder()
                    .id(topicId)
                    .workspaceId(workspaceId)
                    .projectId(projectId)
                    .name("Billing")
                    .definition("Requests about invoices")
                    .born(LocalDate.ofInstant(night, ZoneOffset.UTC))
                    .size(size)
                    .build()));
            topicDAO.insertPrototypes(List.of(
                    TopicPrototype.builder().workspaceId(workspaceId).projectId(projectId).position(0)
                            .topicId(topicId).vector(vector).build(),
                    TopicPrototype.builder().workspaceId(workspaceId).projectId(projectId).position(1)
                            .vector(vector).build()));
            handle.attach(TopicRunDAO.class).insert(TopicRun.builder()
                    .runId(runId)
                    .workspaceId(workspaceId)
                    .projectId(projectId)
                    .mode(TopicRun.Mode.FIRST_BUILD)
                    .status(TopicRun.Status.SAVED)
                    .startedAt(savedAt.minus(Duration.ofMinutes(1)))
                    .finishedAt(savedAt)
                    .build());
            return null;
        });
    }

    List<TopicRun> runs(UUID projectId) {
        return template.inTransaction(READ_ONLY, handle -> workspacesOf(handle, "topic_runs", projectId).stream()
                .flatMap(workspaceId -> handle.attach(TopicRunDAO.class).findByProject(workspaceId, projectId).stream())
                .toList());
    }

    List<Topic> topics(UUID projectId) {
        return template.inTransaction(READ_ONLY, handle -> workspacesOf(handle, "topics", projectId).stream()
                .flatMap(workspaceId -> handle.attach(TopicDAO.class).findLive(workspaceId, projectId).stream())
                .toList());
    }

    List<TopicPrototype> prototypes(UUID projectId) {
        return template.inTransaction(READ_ONLY, handle -> workspacesOf(handle, "topic_prototypes", projectId).stream()
                .flatMap(workspaceId -> handle.attach(TopicDAO.class).findPrototypes(workspaceId, projectId).stream())
                .toList());
    }

    TopicProjectState state(UUID projectId) {
        var states = template.inTransaction(READ_ONLY,
                handle -> workspacesOf(handle, "topic_project_states", projectId).stream()
                        .flatMap(workspaceId -> handle.attach(TopicDAO.class)
                                .findStates(workspaceId, Set.of(projectId)).stream())
                        .toList());
        return states.isEmpty() ? null : states.getFirst();
    }

    /**
     * The workspaces of the project's rows in {@code table}, so the reads above read every row of the project through
     * the workspace-scoped reads: a row written under another workspace still shows.
     */
    private static List<String> workspacesOf(Handle handle, String table, UUID projectId) {
        return handle.createQuery("SELECT DISTINCT workspace_id FROM " + table + " WHERE project_id = :projectId")
                .bind("projectId", projectId.toString())
                .mapTo(String.class)
                .list();
    }

    /**
     * The latest row of every trace of the project, unless that row is a tombstone: the read shape every
     * {@code trace_topics} reader uses. run_id is a UUID v7 string, so on a last_updated_at tie the later run wins.
     */
    List<TraceTopic> traceTopics(String workspaceId, UUID projectId) {
        return clickHouseTemplate.nonTransaction(connection -> Flux.from(connection.createStatement("""
                SELECT workspace_id, project_id, trace_id, trace_created_at, topic_id, source, run_id
                FROM (
                    SELECT workspace_id, project_id, trace_id, trace_created_at, topic_id, source, run_id, is_deleted
                    FROM trace_topics
                    WHERE workspace_id = :workspace_id AND project_id = :project_id
                    ORDER BY workspace_id, project_id, trace_id, last_updated_at DESC, run_id DESC
                    LIMIT 1 BY workspace_id, project_id, trace_id
                )
                WHERE is_deleted = 0
                """)
                .bind("workspace_id", workspaceId)
                .bind("project_id", projectId.toString())
                .execute())
                .flatMap(result -> result.map((row, metadata) -> TraceTopic.builder()
                        .workspaceId(row.get("workspace_id", String.class))
                        .projectId(UUID.fromString(row.get("project_id", String.class)))
                        .traceId(UUID.fromString(row.get("trace_id", String.class)))
                        .traceCreatedAt(row.get("trace_created_at", Instant.class))
                        .topicId(UUID.fromString(row.get("topic_id", String.class)))
                        .source(TraceTopic.Source.valueOf(row.get("source", String.class).toUpperCase()))
                        .runId(row.get("run_id", String.class))
                        .build()))
                .collectList())
                .block();
    }

    /** One raw {@code trace_topics} row with its version and soft-delete marker, as no production write sets them. */
    void insertTraceTopic(String workspaceId, UUID projectId, UUID traceId, Instant traceCreatedAt, UUID topicId,
            Instant lastUpdatedAt, int isDeleted) {
        clickHouseTemplate.nonTransaction(connection -> Mono.from(connection.createStatement("""
                INSERT INTO trace_topics (workspace_id, project_id, trace_id, trace_created_at, topic_id, source,
                    run_id, last_updated_at, is_deleted)
                VALUES (:workspace_id, :project_id, :trace_id, parseDateTime64BestEffort(:trace_created_at, 6),
                    :topic_id, 'run', :run_id, parseDateTime64BestEffort(:last_updated_at, 6), :is_deleted)
                """)
                .bind("workspace_id", workspaceId)
                .bind("project_id", projectId.toString())
                .bind("trace_id", traceId.toString())
                .bind("trace_created_at", traceCreatedAt.toString())
                .bind("topic_id", topicId.toString())
                .bind("run_id", UUID.randomUUID().toString())
                .bind("last_updated_at", lastUpdatedAt.toString())
                .bind("is_deleted", isDeleted)
                .execute())
                .flatMap(result -> Mono.from(result.getRowsUpdated())))
                .block();
    }

    /**
     * A tombstone ({@code is_deleted = 1}) for each of the traces' vector rows, stamped {@code offsetSeconds} from now,
     * as no production write sets one yet.
     */
    void insertVectorTombstones(String workspaceId, UUID projectId, List<UUID> traceIds, int offsetSeconds) {
        insertVectorRows(workspaceId, projectId, traceIds, "task_version", "addSeconds(now64(6), %d)"
                .formatted(offsetSeconds), 1, "");
    }

    /** Runs {@code action} with the merges of {@code topic_vectors} stopped, so its rows stay as written. */
    void withoutMerges(Runnable action) {
        systemMerges("STOP");
        try {
            action.run();
        } finally {
            systemMerges("START");
        }
    }

    private void systemMerges(String verb) {
        clickHouseTemplate.nonTransaction(connection -> Mono.from(connection
                .createStatement("SYSTEM " + verb + " MERGES topic_vectors").execute())
                .flatMap(result -> Mono.from(result.getRowsUpdated())))
                .block();
    }

    /** A tombstone for each of the traces' vector rows, stamped at the same time as the row it copies. */
    void insertTiedVectorTombstones(String workspaceId, UUID projectId, List<UUID> traceIds) {
        insertVectorRows(workspaceId, projectId, traceIds, "task_version", "last_updated_at", 1, "");
    }

    /** As {@link #insertVectorTombstones}, for the traces' rows of task version 1 only. */
    void insertVersionOneTombstones(String workspaceId, UUID projectId, List<UUID> traceIds, int offsetSeconds) {
        insertVectorRows(workspaceId, projectId, traceIds, "task_version", "addSeconds(now64(6), %d)"
                .formatted(offsetSeconds), 1, "AND task_version = 1");
    }

    /** A live task version 2 row for each of the traces, a copy of its version 1 row: a relabel. */
    void relabel(String workspaceId, UUID projectId, List<UUID> traceIds) {
        insertVectorRows(workspaceId, projectId, traceIds, "2", "now64(6)", 0, "AND task_version = 1");
    }

    private void insertVectorRows(String workspaceId, UUID projectId, List<UUID> traceIds, String taskVersion,
            String lastUpdatedAt, int isDeleted, String filter) {
        clickHouseTemplate.nonTransaction(connection -> Mono.from(connection.createStatement("""
                INSERT INTO topic_vectors (workspace_id, project_id, trace_id, trace_created_at, task_version,
                    embed_model, embed_dimensions, text, vector, last_updated_at, is_deleted)
                SELECT workspace_id, project_id, trace_id, trace_created_at, %s, embed_model,
                    embed_dimensions, text, vector, %s, %d
                FROM topic_vectors
                WHERE workspace_id = :workspace_id AND project_id = :project_id AND trace_id IN :trace_ids %s
                """.formatted(taskVersion, lastUpdatedAt, isDeleted, filter))
                .bind("workspace_id", workspaceId)
                .bind("project_id", projectId.toString())
                .bind("trace_ids", traceIds.stream().map(UUID::toString).toArray(String[]::new))
                .execute())
                .flatMap(result -> Mono.from(result.getRowsUpdated())))
                .block();
    }

    /** opik-inference's {@code /v1/generate} response with {@code output} as its result. */
    static ResponseDefinitionBuilder answer(JsonNode output, int delayMillis) {
        ObjectNode body = InferenceJson.MAPPER.createObjectNode();
        body.put("request_id", "r-1");
        body.putObject("usage").put("input_tokens", 1_000).put("output_tokens", 200);
        body.put("cache_hit", false);
        body.put("latency_ms", 1_500);
        body.putObject("result").set("output", output);
        return aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withFixedDelay(delayMillis)
                .withBody(body.toString());
    }

    static JsonNode json(String text) {
        try {
            return InferenceJson.MAPPER.readTree(text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
