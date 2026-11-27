package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.Handle;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static org.assertj.core.api.Assertions.assertThat;

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
     * the {@code i}-th {@link TopicAlgorithm#DIMENSIONS} floats of {@code vectors}. {@code night} is the
     * start of the day after the last trace, so the traces are the window of that night.
     */
    record Traces(List<UUID> traceIds, List<Instant> createdAt, String[] texts, float[] vectors, Instant night) {

        /** {@code count} traces of random unit vectors, ten minutes apart, the last one ten minutes before night. */
        static Traces synthetic(int count, Instant night, long seed) {
            var step = Duration.ofMinutes(10);
            return of(count, night, night.minus(step.multipliedBy(count)), step, new Random(seed),
                    Generators.timeBasedEpochGenerator());
        }

        /**
         * {@code count} traces of random unit vectors spread evenly over the {@code days} before night. The ids are
         * UUID v7 with tails from {@code seed}, so the same seed gives the same ids.
         */
        static Traces spread(int count, Instant night, int days, long seed) {
            var random = new Random(seed);
            return of(count, night, night.minus(Duration.ofDays(days)), Duration.ofDays(days).dividedBy(count), random,
                    Generators.timeBasedEpochGenerator(random));
        }

        /** Trace {@code i} at {@code first + i * step}; its id, then its vector, are drawn in trace order. */
        private static Traces of(int count, Instant night, Instant first, Duration step, Random random,
                TimeBasedEpochGenerator ids) {
            int dimensions = TopicEmbedding.DIMENSIONS;
            var traceIds = new ArrayList<UUID>(count);
            var createdAt = new ArrayList<Instant>(count);
            var texts = new String[count];
            var vectors = new float[count * dimensions];
            for (int i = 0; i < count; i++) {
                var created = first.plus(step.multipliedBy(i));
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
        seedVectors(workspaceId, projectId, from, count, night);
    }

    /** As {@link #seed(String, UUID, int, int, Instant)}, the vectors only: the project row is left as it is. */
    void seedVectors(String workspaceId, UUID projectId, int from, int count, Instant night) {
        var shift = Duration.between(traces.night(), night);
        int dimensions = TopicEmbedding.DIMENSIONS;
        var rows = new ArrayList<TopicVector>(count);
        for (int i = from; i < from + count; i++) {
            var vector = Arrays.copyOfRange(traces.vectors(), i * dimensions, (i + 1) * dimensions);
            rows.add(TopicVector.builder()
                    .workspaceId(workspaceId)
                    .projectId(projectId)
                    .task(TopicEmbedding.SUMMARY_TASK)
                    .traceId(traces.traceIds().get(i))
                    .traceCreatedAt(traces.createdAt().get(i).plus(shift))
                    .taskVersion(1)
                    .embedModel(TopicEmbedding.MODEL)
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
     * What a saved discovery run leaves in MySQL for a project: its row with the run saved two hours after
     * {@code night}, and a topic of {@code size} traces. The labels are not complete yet.
     */
    void savedList(String workspaceId, UUID projectId, Instant night, int size) {
        var runId = newId();
        var savedAt = night.plus(Duration.ofHours(2));
        var vectors = new PrototypeVectors(List.of(unit(0)));
        template.inTransaction(WRITE, handle -> {
            var projectDAO = handle.attach(TopicProjectDAO.class);
            var topicDAO = handle.attach(TopicDAO.class);
            startRun(projectDAO, workspaceId, projectId, runId, TopicProject.RunMode.DISCOVERY,
                    savedAt.minus(Duration.ofMinutes(1)));
            projectDAO.saveList(workspaceId, projectId, null, runId, savedAt, night,
                    new PrototypeVectors(List.of(unit(1))), TopicEmbedding.MODEL);
            topicDAO.insertTopics(List.of(Topic.builder()
                    .id(newId())
                    .workspaceId(workspaceId)
                    .projectId(projectId)
                    .name("Billing")
                    .definition("Requests about invoices")
                    .born(LocalDate.ofInstant(night, ZoneOffset.UTC))
                    .size(size)
                    .vectors(vectors)
                    .build()));
            projectDAO.markSaved(workspaceId, projectId, runId, savedAt);
            return null;
        });
    }

    /**
     * Puts {@code runId} on the project's row, running since {@code at} with a deadline an hour later, as the
     * planner's queue and a pod's claim do. The project must have no run in flight.
     */
    static void startRun(TopicProjectDAO projectDAO, String workspaceId, UUID projectId, UUID runId,
            TopicProject.RunMode mode, Instant at) {
        projectDAO.ensureRow(workspaceId, projectId);
        if (projectDAO.queue(workspaceId, projectId, runId, mode, at) != 1
                || projectDAO.claim(workspaceId, projectId, runId, at, at.plus(Duration.ofHours(1))) != 1) {
            throw new IllegalStateException("project '%s' has a run in flight".formatted(projectId));
        }
    }

    /** An Opik id: UUID v7. */
    /** The Reactor context a run's subscriber gives its work, for code that reads the workspace from it. */
    static Context context(TopicsMetrics.Workspace workspace) {
        return Context.of(RequestContext.WORKSPACE_ID, workspace.id(), RequestContext.WORKSPACE_NAME, workspace.name(),
                RequestContext.USER_NAME, "test");
    }

    static Context context(String workspaceId) {
        return context(new TopicsMetrics.Workspace(workspaceId, workspaceId));
    }

    static UUID newId() {
        return Generators.timeBasedEpochGenerator().generate();
    }

    /** The run id of the run rows {@link #insertTraceTopic} writes, so a test can build the rows it expects. */
    static final UUID RUN_ID = newId();

    /** A unit vector along {@code axis}. */
    static float[] unit(int axis) {
        var vector = new float[TopicEmbedding.DIMENSIONS];
        vector[axis] = 1;
        return vector;
    }

    List<Topic> topics(UUID projectId) {
        return template.inTransaction(READ_ONLY, handle -> workspacesOf(handle, "topics", projectId).stream()
                .flatMap(workspaceId -> handle.attach(TopicDAO.class).findByProject(workspaceId, projectId).stream())
                .toList());
    }

    TopicProject topicProject(UUID projectId) {
        var rows = template.inTransaction(READ_ONLY,
                handle -> workspacesOf(handle, "topic_projects", projectId).stream()
                        .flatMap(workspaceId -> handle.attach(TopicProjectDAO.class)
                                .findProjects(workspaceId, Set.of(projectId)).stream())
                        .toList());
        return rows.isEmpty() ? null : rows.getFirst();
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
     * What topic on arrival relies on: the bound algorithm's labeller, made from the list the run saved in MySQL (the
     * project's topics and its known-Other vectors), gives every trace the run labelled the label the run wrote to
     * {@code trace_topics}. {@code night} is the end of the run's window.
     */
    void assertArrivalEqualsTheRun(TopicWindowDAO windowDAO, TopicAlgorithm algorithm, String workspaceId,
            UUID projectId, UUID runId, Instant night) {
        var labels = traceTopics(workspaceId, projectId).stream()
                .filter(row -> runId.equals(row.runId()))
                .collect(Collectors.toMap(TraceTopic::traceId, TraceTopic::topicId));
        // Wider than the test's window: only the run's traces are compared.
        var window = windowDAO.readWindow(projectId, night.minus(Duration.ofDays(90)), night.plus(Duration.ofDays(1)),
                100_000, 120)
                .contextWrite(context(workspaceId))
                .collectList().block()
                .stream().filter(row -> labels.containsKey(row.traceId())).toList();
        assertThat(window).hasSize(labels.size()).isNotEmpty();

        var arrived = algorithm.labeller(topics(projectId), topicProject(projectId).otherVectors())
                .label(window.stream().map(TopicWindowDAO.WindowRow::vector).toList());
        for (int i = 0; i < window.size(); i++) {
            assertThat(arrived[i]).as("trace '%s'", window.get(i).traceId())
                    .isEqualTo(labels.get(window.get(i).traceId()));
        }
    }

    /**
     * The latest row of every trace of the project, unless that row is a tombstone: the read shape every
     * {@code trace_topics} reader uses, a run row winning a tie with an arrival row.
     */
    List<TraceTopic> traceTopics(String workspaceId, UUID projectId) {
        return clickHouseTemplate.nonTransaction(connection -> Flux.from(connection.createStatement("""
                SELECT workspace_id, project_id, trace_id, trace_created_at, topic_id, source, run_id
                FROM (
                    SELECT workspace_id, project_id, trace_id, trace_created_at, topic_id, source, run_id, is_deleted
                    FROM trace_topics
                    WHERE workspace_id = :workspace_id AND project_id = :project_id
                    ORDER BY workspace_id, project_id, trace_id, last_updated_at DESC, source = 'run' DESC
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
                        .runId(Optional.ofNullable(row.get("run_id", String.class)).map(UUID::fromString)
                                .orElse(null))
                        .build()))
                .collectList())
                .block();
    }

    /**
     * One raw run row of {@code trace_topics} with its version and soft-delete marker, as no production write sets
     * them.
     */
    void insertTraceTopic(String workspaceId, UUID projectId, UUID traceId, Instant traceCreatedAt, UUID topicId,
            Instant lastUpdatedAt, int isDeleted) {
        insertTraceTopic(workspaceId, projectId, traceId, traceCreatedAt, topicId, TraceTopic.Source.RUN,
                lastUpdatedAt, isDeleted);
    }

    /** As {@link #insertTraceTopic(String, UUID, UUID, Instant, UUID, Instant, int)}, from {@code source}. */
    void insertTraceTopic(String workspaceId, UUID projectId, UUID traceId, Instant traceCreatedAt, UUID topicId,
            TraceTopic.Source source, Instant lastUpdatedAt, int isDeleted) {
        clickHouseTemplate.nonTransaction(connection -> Mono.from(connection.createStatement("""
                INSERT INTO trace_topics (workspace_id, project_id, trace_id, trace_created_at, topic_id, source,
                    run_id, last_updated_at, is_deleted)
                VALUES (:workspace_id, :project_id, :trace_id, parseDateTime64BestEffort(:trace_created_at, 6),
                    :topic_id, :source, nullIf(:run_id, ''), parseDateTime64BestEffort(:last_updated_at, 6),
                    :is_deleted)
                """)
                .bind("workspace_id", workspaceId)
                .bind("project_id", projectId.toString())
                .bind("trace_id", traceId.toString())
                .bind("trace_created_at", traceCreatedAt.toString())
                .bind("topic_id", topicId.toString())
                .bind("source", source.getValue())
                .bind("run_id", source == TraceTopic.Source.RUN ? RUN_ID.toString() : "")
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
                INSERT INTO topic_vectors (workspace_id, project_id, task, trace_id, trace_created_at, task_version,
                    embed_model, embed_dimensions, text, vector, last_updated_at, is_deleted)
                SELECT workspace_id, project_id, task, trace_id, trace_created_at, %s, embed_model,
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

    /**
     * The distinct {@code max_threads}, {@code max_rows_to_read}, {@code priority} and {@code max_execution_time} the
     * workspace's finished {@code queryName} queries ran with, an empty string for a setting left at its default.
     */
    List<List<String>> queryGuards(String queryName, String workspaceId) {
        return queryLogSettings(queryName, workspaceId, "max_threads", "max_rows_to_read", "priority",
                "max_execution_time");
    }

    /** The distinct values of the named settings the workspace's finished {@code queryName} queries ran with. */
    List<List<String>> queryLogSettings(String queryName, String workspaceId, String... names) {
        return clickHouseTemplate.nonTransaction(connection -> Mono
                .from(connection.createStatement("SYSTEM FLUSH LOGS").execute())
                .flatMap(result -> Mono.from(result.getRowsUpdated()))
                .thenMany(Flux.from(connection.createStatement("""
                        SELECT DISTINCT arrayMap(name -> Settings[name], :names) AS settings
                        FROM system.query_log
                        WHERE type = 'QueryFinish' AND is_initial_query AND log_comment LIKE :log_comment
                        """)
                        .bind("names", names)
                        .bind("log_comment", queryName + ":" + workspaceId + ":%")
                        .execute()))
                .flatMap(result -> result.map((row, metadata) -> List.of(row.get("settings", String[].class))))
                .collectList())
                .block();
    }

    /** How many {@code queryName} queries of the workspaces finished, from {@code system.query_log}. */
    long finishedQueries(String queryName, Collection<String> workspaceIds) {
        return clickHouseTemplate.nonTransaction(connection -> Mono
                .from(connection.createStatement("SYSTEM FLUSH LOGS").execute())
                .flatMap(result -> Mono.from(result.getRowsUpdated()))
                .then(Mono.from(connection.createStatement("""
                        SELECT count() AS cnt
                        FROM system.query_log
                        WHERE type = 'QueryFinish' AND is_initial_query
                        AND startsWith(log_comment, concat(:query_name, ':'))
                        AND has(:workspace_ids, splitByChar(':', log_comment)[3])
                        """)
                        .bind("query_name", queryName)
                        .bind("workspace_ids", workspaceIds.toArray(String[]::new))
                        .execute()))
                .flatMap(result -> Mono.from(result.map((row, metadata) -> row.get("cnt", Long.class)))))
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
