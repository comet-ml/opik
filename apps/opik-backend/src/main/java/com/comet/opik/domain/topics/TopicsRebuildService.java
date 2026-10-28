package com.comet.opik.domain.topics;

import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.topics.TopicsRunException.Code;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.inference.GenerateRequest;
import com.comet.opik.infrastructure.inference.InferenceClient;
import com.comet.opik.infrastructure.inference.InferenceException;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.comet.opik.infrastructure.inference.ItemResponse.GenerateResponse;
import com.comet.opik.infrastructure.inference.Message;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.net.SocketTimeoutException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/**
 * One project's run of the nightly job. Stage 2a has the first build only: read the window of vectors, merge identical
 * texts, k-means them into fine groups, have Qwen name topics over the groups, assign every window trace to its
 * nearest prototype, check the invariants and save. Synchronous: the nightly job calls it on its own thread, one
 * project at a time.
 *
 * <p>The save writes {@code trace_topics} first, then one MySQL transaction: the new topics, the project's prototypes
 * (replaced), its state and the run as {@code saved}. A failure before the save writes nothing but the failed run; a
 * MySQL failure after the ClickHouse insert leaves rows no saved run points at, which the next night's insert replaces
 * (the latest row per trace wins).
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicsRebuildService {

    static final int DIMENSIONS = TopicVectorsTickService.EMBED_DIMENSIONS;
    static final int MAX_TOPICS = 10;
    static final int MIN_GROUPS = 15;
    static final int MAX_GROUPS = 50;
    static final int TEXTS_PER_GROUP = 60;
    static final int RECENT_DAYS = 7;
    static final int QWEN_MAX_TOKENS = 2500;
    /** A first build over fewer traces is marked provisional in its stats. */
    static final int PROVISIONAL_BELOW = 1_000;
    static final double UNIT_TOLERANCE = 1e-3;

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TransactionTemplate template;
    private final @NonNull TopicWindowDAO windowDAO;
    private final @NonNull TraceTopicsDAO traceTopicsDAO;
    private final @NonNull InferenceClient client;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TopicsMetrics metrics;

    /** How a run ended. {@code errorCode} is null for a saved run. */
    @Builder(toBuilder = true)
    public record Outcome(@NonNull UUID runId, @NonNull TopicRun.Status status, String errorCode) {
    }

    /** Inserts the run as {@code running} and returns its id. */
    public UUID startRun(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull TopicRun.Mode mode) {
        var runId = idGenerator.generateId();
        template.inTransaction(WRITE, handle -> {
            handle.attach(TopicRunDAO.class).insert(TopicRun.builder()
                    .runId(runId)
                    .workspaceId(workspaceId)
                    .projectId(projectId)
                    .mode(mode)
                    .status(TopicRun.Status.RUNNING)
                    .startedAt(Instant.now())
                    .build());
            return null;
        });
        return runId;
    }

    /**
     * Fails the run unless it already ended. Returns false when it had: the run was saved, or failed by its own thread
     * and its timeout both.
     */
    public boolean fail(@NonNull UUID runId, @NonNull Code code, @NonNull String message, String stats) {
        int updated = template.inTransaction(WRITE, handle -> handle.attach(TopicRunDAO.class).markFailed(runId,
                Instant.now(), code.getValue(), StringUtils.truncate(message, TopicRun.MAX_ERROR_MESSAGE), stats));
        if (updated == 1) {
            metrics.run(TopicRun.Status.FAILED, code.getValue());
        }
        return updated == 1;
    }

    /**
     * Builds the project's first topic list for {@code night}, the UTC date whose start ends the window. Never throws:
     * a failure is recorded on the run and returned.
     */
    public Outcome firstBuild(@NonNull UUID runId, @NonNull String workspaceId, @NonNull UUID projectId,
            @NonNull LocalDate night) {
        var run = new Run(runId, workspaceId, projectId, night);
        try {
            run.build();
            return Outcome.builder().runId(runId).status(TopicRun.Status.SAVED).build();
        } catch (TopicsRunException exception) {
            return run.failed(exception.getCode(), exception);
        } catch (CancellationException exception) {
            return run.failed(Code.TIMEOUT, exception);
        } catch (RuntimeException exception) {
            return run.failed(Thread.currentThread().isInterrupted() ? Code.TIMEOUT : Code.INTERNAL, exception);
        }
    }

    /** One run's state while it builds: the stats it records and the phase timings. */
    private final class Run {

        private final UUID runId;
        private final String workspaceId;
        private final UUID projectId;
        private final LocalDate night;
        private final Instant from;
        private final Instant to;
        private final ObjectNode stats = JsonUtils.createObjectNode();
        private final ObjectNode seconds = JsonUtils.createObjectNode();
        /** The project's state when the run started: the save checks that no other run saved since. */
        private Optional<TopicProjectState> readState = Optional.empty();

        Run(UUID runId, String workspaceId, UUID projectId, LocalDate night) {
            this.runId = runId;
            this.workspaceId = workspaceId;
            this.projectId = projectId;
            this.night = night;
            this.to = night.atStartOfDay(ZoneOffset.UTC).toInstant();
            this.from = night.minusDays(config.getNightly().getWindowDays()).atStartOfDay(ZoneOffset.UTC).toInstant();
            stats.put("window_from", from.toString());
            stats.put("window_until", to.toString());
        }

        void build() {
            readState = template.inTransaction(READ_ONLY, handle -> handle.attach(TopicDAO.class)
                    .findStates(List.of(projectId)).stream().findFirst());
            var rows = timed("window", this::readWindow);
            int n = rows.size();
            var texts = new String[n];
            var vectors = new float[n * DIMENSIONS];
            var recent = new boolean[n];
            var recentFrom = night.minusDays(RECENT_DAYS).atStartOfDay(ZoneOffset.UTC).toInstant();
            var versions = new TreeMap<Long, Integer>();
            for (int i = 0; i < n; i++) {
                var row = rows.get(i);
                texts[i] = row.text();
                System.arraycopy(row.vector(), 0, vectors, i * DIMENSIONS, DIMENSIONS);
                recent[i] = !row.traceCreatedAt().isBefore(recentFrom);
                versions.merge(row.taskVersion(), 1, Integer::sum);
            }
            var versionShares = stats.putObject("task_version_shares");
            versions.forEach((version, count) -> versionShares.put(String.valueOf(version), (double) count / n));

            var merged = IntentMerger.merge(texts, vectors, DIMENSIONS);
            int unique = merged.size();
            int k = Math.min(Math.min(MAX_GROUPS, Math.max(MIN_GROUPS, unique / TEXTS_PER_GROUP)), unique);
            // The seed is the project's saved nights: 0 for the first build.
            long seed = 0;
            var weights = new double[unique];
            for (int u = 0; u < unique; u++) {
                weights[u] = merged.weights()[u];
            }
            var fit = timed("kmeans", () -> KMeans.fit(merged.vectors(), weights, DIMENSIONS, k, seed));
            var groupOf = new int[n];
            for (int i = 0; i < n; i++) {
                groupOf[i] = fit.labels()[merged.inverse()[i]];
            }
            stats.put("vectors", n);
            stats.put("unique_texts", unique);
            stats.put("k", k);
            stats.put("seed", seed);
            stats.put("kmeans_inertia", fit.inertia());
            stats.put("kmeans_iterations", fit.iterations());
            stats.put("provisional", n < PROVISIONAL_BELOW);

            var groups = GroupSummaries.summarize(texts, vectors, groupOf, fit.centres(), recent, DIMENSIONS);
            var prompt = TopicPrompts.author(groups, MAX_TOPICS);
            // The prompt and the answer hold trace-derived text: DEBUG only, never in production logs by default.
            log.debug("Topics run '{}' author prompt, project '{}':\n{}", runId, projectId, prompt);
            var answer = timed("qwen", () -> author(prompt));
            var applied = AuthorApplier.apply(answer, groups, MAX_TOPICS);

            var assigned = timed("assign", () -> assign(applied, fit.centres(), vectors, n));
            var topics = topics(applied, assigned.labels());
            var prototypes = prototypes(assigned, topics);
            checkInvariants(topics, prototypes, assigned.labels(), n);

            int other = 0;
            for (int label : assigned.labels()) {
                other += label == PrototypeAssigner.OTHER ? 1 : 0;
            }
            stats.put("topics", topics.size());
            stats.put("other_share", (double) other / n);
            stats.put("other_groups", applied.other().size());
            stats.put("gated_other", assigned.assigner().gatedOther());
            stats.put("prototypes", prototypes.size());
            stats.set("seconds", seconds);
            log.info("Topics run '{}' built, project '{}', window ['{}', '{}'), vectors '{}', unique texts '{}', "
                    + "k '{}', seed '{}', topic sizes '{}', Other share '{}', seconds '{}'", runId, projectId, from, to,
                    n, unique, k, seed, topics.stream().map(Topic::size).toList(),
                    TopicText.fixed((double) other / n, 3), seconds);
            // Topic names come from trace text: DEBUG only.
            log.debug("Topics run '{}' topics, project '{}': '{}'", runId, projectId,
                    topics.stream().map(topic -> topic.name() + " (" + topic.size() + ")").toList());

            timed("save", () -> {
                save(rows, assigned.labels(), topics, prototypes);
                return null;
            });
        }

        private List<TopicWindowRow> readWindow() {
            List<TopicWindowRow> rows;
            try {
                rows = windowDAO.readWindow(workspaceId, projectId, from, to, config.getNightly().getMaxVectors(),
                        Math.max(1, config.getNightly().getProjectTimeout().toSeconds())).collectList().block();
            } catch (RuntimeException exception) {
                throw new TopicsRunException(Code.WINDOW_READ, "the window read failed", exception);
            }
            var kept = new ArrayList<TopicWindowRow>(rows.size());
            int skipped = 0;
            for (var row : rows) {
                var text = TopicText.pythonStrip(row.text());
                if (text.isEmpty() || row.vector().length != DIMENSIONS) {
                    skipped++;
                    continue;
                }
                kept.add(row.toBuilder().text(text).build());
            }
            // Time order, earliest first: the merge keeps each text's first trace.
            kept.sort(Comparator.comparing(TopicWindowRow::traceCreatedAt).thenComparing(TopicWindowRow::traceId));
            stats.put("window_rows", rows.size());
            stats.put("skipped_rows", skipped);
            if (kept.isEmpty()) {
                throw new TopicsRunException(Code.WINDOW_READ, "the window has no usable vector");
            }
            return kept;
        }

        private AuthorAnswer author(String prompt) {
            var request = GenerateRequest.builder()
                    .orgId(workspaceId)
                    .workspaceId(workspaceId)
                    .requestId(runId.toString())
                    .cache(true)
                    .lane(GenerateRequest.Lane.BATCH)
                    .messages(List.of(Message.builder().role(Message.Role.USER).content(prompt).build()))
                    .schema(TopicPrompts.AUTHOR_SCHEMA)
                    .maxTokens(QWEN_MAX_TOKENS)
                    .temperature(0)
                    .build();
            stats.put("qwen_calls", 1);
            GenerateResponse response;
            try {
                response = client.generate(request,
                        Instant.now().plus(config.getNightly().getQwenDeadline().toJavaDuration())).block();
            } catch (InferenceException exception) {
                if (exception.isSchemaViolation()) {
                    // The service checks the answer against the schema: a cut or off-schema answer comes back so.
                    metrics.qwen("bad_answer");
                    throw new TopicsRunException(Code.QWEN_BAD_ANSWER,
                            "the Qwen answer breaks the schema, status '%s'".formatted(exception.getHttpStatus()),
                            exception);
                }
                boolean timeout = isTimeout(exception);
                metrics.qwen(timeout ? "timeout" : "error");
                throw new TopicsRunException(timeout ? Code.QWEN_TIMEOUT : Code.QWEN_ERROR,
                        "the Qwen call failed, code '%s', status '%s'".formatted(exception.getCode(),
                                exception.getHttpStatus()),
                        exception);
            }
            var output = response.result().output();
            log.info("Topics run '{}' author answer, project '{}', input tokens '{}', output tokens '{}', cache hit "
                    + "'{}'", runId, projectId, response.usage().inputTokens(), response.usage().outputTokens(),
                    response.cacheHit());
            log.debug("Topics run '{}' author answer, project '{}':\n{}", runId, projectId,
                    output == null ? "null" : InferenceJson.canonical(output));
            stats.put("qwen_input_tokens", response.usage().inputTokens());
            stats.put("qwen_output_tokens", response.usage().outputTokens());
            stats.put("qwen_cache_hit", response.cacheHit());
            try {
                var answer = AuthorAnswer.parse(output);
                metrics.qwen("ok");
                return answer;
            } catch (TopicsRunException exception) {
                metrics.qwen("bad_answer");
                throw exception;
            }
        }

        private Assigned assign(AuthorApplier.Applied applied, float[] centres, float[] vectors, int n) {
            var topicPrototypes = new ArrayList<float[]>();
            var owners = new ArrayList<Integer>();
            for (int t = 0; t < applied.topics().size(); t++) {
                for (int group : applied.topics().get(t).groups()) {
                    topicPrototypes.add(centre(centres, group));
                    owners.add(t);
                }
            }
            var otherPrototypes = applied.other().stream().map(group -> centre(centres, group)).toList();
            var assigner = new PrototypeAssigner(topicPrototypes, owners, otherPrototypes, DIMENSIONS);
            return new Assigned(assigner, assigner.assign(vectors), topicPrototypes, owners, otherPrototypes);
        }

        private List<Topic> topics(AuthorApplier.Applied applied, int[] labels) {
            var sizes = new int[applied.topics().size()];
            for (int label : labels) {
                if (label != PrototypeAssigner.OTHER) {
                    sizes[label]++;
                }
            }
            var topics = new ArrayList<Topic>(sizes.length);
            for (int t = 0; t < sizes.length; t++) {
                var topic = applied.topics().get(t);
                topics.add(Topic.builder()
                        .id(idGenerator.generateId())
                        .workspaceId(workspaceId)
                        .projectId(projectId)
                        .name(topic.name())
                        .definition(topic.definition())
                        .born(night)
                        .size(sizes[t])
                        .build());
            }
            return topics;
        }

        /**
         * The topic prototypes, then every known-Other one. The 0.95 gate drops an Other prototype near a topic from
         * tonight's assignment only: it is saved, as on the stand, and the next night's update assigns with it.
         */
        private List<TopicPrototype> prototypes(Assigned assigned, List<Topic> topics) {
            var prototypes = new ArrayList<TopicPrototype>(
                    assigned.topicPrototypes().size() + assigned.otherPrototypes().size());
            for (int p = 0; p < assigned.topicPrototypes().size(); p++) {
                prototypes.add(prototype(prototypes.size(), topics.get(assigned.owners().get(p)).id(),
                        assigned.topicPrototypes().get(p)));
            }
            for (var vector : assigned.otherPrototypes()) {
                prototypes.add(prototype(prototypes.size(), null, vector));
            }
            return prototypes;
        }

        private TopicPrototype prototype(int position, UUID topicId, float[] vector) {
            return TopicPrototype.builder()
                    .id(idGenerator.generateId())
                    .workspaceId(workspaceId)
                    .projectId(projectId)
                    .position(position)
                    .topicId(topicId)
                    .vector(TopicPrototype.encode(vector))
                    .build();
        }

        /** D5: what must hold before anything is written. */
        private void checkInvariants(List<Topic> topics, List<TopicPrototype> prototypes, int[] labels, int n) {
            if (topics.size() > MAX_TOPICS) {
                throw invariant("'%s' topics, at most '%s'".formatted(topics.size(), MAX_TOPICS));
            }
            for (var topic : topics) {
                if (StringUtils.isBlank(topic.name()) || AuthorApplier.isCatchAll(topic.name())) {
                    throw invariant("topic '%s' has a blank or catch-all name".formatted(topics.indexOf(topic)));
                }
                if (!topic.born().equals(night)) {
                    throw invariant("topic '%s' born '%s', not tonight".formatted(topics.indexOf(topic), topic.born()));
                }
            }
            for (var prototype : prototypes) {
                var values = prototype.values();
                double norm = 0;
                for (float value : values) {
                    norm += (double) value * value;
                }
                if (values.length != DIMENSIONS || Math.abs(Math.sqrt(norm) - 1) > UNIT_TOLERANCE) {
                    throw invariant("prototype '%s' has '%s' values and norm '%s'".formatted(prototype.position(),
                            values.length, Math.sqrt(norm)));
                }
            }
            if (labels.length != n) {
                throw invariant("'%s' labels for '%s' traces".formatted(labels.length, n));
            }
            for (int label : labels) {
                if (label != PrototypeAssigner.OTHER && (label < 0 || label >= topics.size())) {
                    throw invariant("a trace labelled '%s' with '%s' topics".formatted(label, topics.size()));
                }
            }
        }

        /**
         * Deletes the trace_topics rows of a run whose MySQL save did not commit. Best effort: a row left behind
         * belongs to a run that never saved, and the next saved run replaces it. It runs after any insert
         * attempt, as a failed insert may have reached ClickHouse, and clears the interrupt of a timed-out run while it
         * deletes: {@code block()} on an interrupted thread throws at once.
         */
        private void takeBack(boolean attempted) {
            if (!attempted) {
                return;
            }
            boolean interrupted = Thread.interrupted();
            try {
                traceTopicsDAO.deleteByRunId(workspaceId, projectId, runId).block();
            } catch (RuntimeException exception) {
                log.warn("Topics run '{}' could not delete its trace_topics rows after a failed save, project '{}'",
                        runId, projectId, exception);
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        /**
         * Writes the project's state only if no other run saved the project since this run read it. The Redis project
         * lock keeps two runs apart, but a Redis failover or a timed-out run that outlives its lock can break that.
         * It first locks the project's row: a project deleted during the run fails the save, and a delete that comes
         * later waits for this transaction, so its cleanup removes what the run saved.
         */
        private void writeState(TopicDAO topicDAO, TopicProjectState state) {
            if (topicDAO.lockProject(workspaceId, projectId) == 0) {
                throw new TopicsRunException(Code.SAVE, "the project was deleted during the run");
            }
            if (readState.isEmpty()) {
                try {
                    topicDAO.insertState(state);
                } catch (UnableToExecuteStatementException exception) {
                    if (exception.getCause() instanceof SQLIntegrityConstraintViolationException) {
                        throw conflict(exception);
                    }
                    throw exception;
                }
            } else if (topicDAO.updateState(state, readState.get().lastRunId()) != 1) {
                throw conflict(null);
            }
        }

        private TopicsRunException conflict(Exception cause) {
            return new TopicsRunException(Code.SAVE, "another run saved this project meanwhile", cause);
        }

        private void save(List<TopicWindowRow> rows, int[] labels, List<Topic> topics,
                List<TopicPrototype> prototypes) {
            var traceTopics = new ArrayList<TraceTopic>(rows.size());
            for (int i = 0; i < rows.size(); i++) {
                traceTopics.add(TraceTopic.builder()
                        .workspaceId(workspaceId)
                        .projectId(projectId)
                        .traceId(rows.get(i).traceId())
                        .traceCreatedAt(rows.get(i).traceCreatedAt())
                        .topicId(labels[i] == PrototypeAssigner.OTHER ? TraceTopic.OTHER : topics.get(labels[i]).id())
                        .source(TraceTopic.Source.NIGHT)
                        .runId(runId.toString())
                        .build());
            }
            var statsJson = stats.toString();
            boolean attempted = false;
            try {
                attempted = true;
                traceTopicsDAO.insert(traceTopics).block();
                var now = Instant.now();
                template.inTransaction(WRITE, handle -> {
                    var topicDAO = handle.attach(TopicDAO.class);
                    // First, so a run that saved this project meanwhile fails the save before anything else.
                    writeState(topicDAO, TopicProjectState.builder()
                            .projectId(projectId)
                            .workspaceId(workspaceId)
                            .rebuilds(1)
                            .firstBuiltAt(now)
                            .lastRunId(runId)
                            .lastSuccessAt(now)
                            .windowUntil(to)
                            .build());
                    if (!topics.isEmpty()) {
                        topicDAO.insertTopics(topics);
                    }
                    topicDAO.deletePrototypes(projectId);
                    if (!prototypes.isEmpty()) {
                        topicDAO.insertPrototypes(prototypes);
                    }
                    if (handle.attach(TopicRunDAO.class).markSaved(runId, now, statsJson) != 1) {
                        // Failed meanwhile, by its timeout: roll the save back.
                        throw new TopicsRunException(Code.TIMEOUT, "the run ended before its save committed");
                    }
                    return null;
                });
            } catch (TopicsRunException exception) {
                takeBack(attempted);
                throw exception;
            } catch (RuntimeException exception) {
                takeBack(attempted);
                throw new TopicsRunException(Code.SAVE, "the save failed", exception);
            }
            metrics.run(TopicRun.Status.SAVED, null);
            metrics.saved("trace_topics", traceTopics.size());
            metrics.saved("topics", topics.size());
            metrics.saved("topic_prototypes", prototypes.size());
            log.info("Topics run '{}' saved, project '{}', trace topics '{}', topics '{}', prototypes '{}'", runId,
                    projectId, traceTopics.size(), topics.size(), prototypes.size());
        }

        Outcome failed(Code code, Exception exception) {
            stats.set("seconds", seconds);
            var message = StringUtils.defaultIfBlank(ExceptionUtils.getMessage(exception), code.getValue());
            log.warn("Topics run '{}' failed, project '{}', code '{}'", runId, projectId, code.getValue(), exception);
            try {
                fail(runId, code, message, stats.toString());
            } catch (RuntimeException markException) {
                log.error("Topics run '{}' could not be marked failed, project '{}'", runId, projectId,
                        markException);
            }
            return Outcome.builder().runId(runId).status(TopicRun.Status.FAILED).errorCode(code.getValue()).build();
        }

        private <T> T timed(String phase, Supplier<T> action) {
            long started = System.nanoTime();
            try {
                return action.get();
            } finally {
                var duration = Duration.ofNanos(System.nanoTime() - started);
                seconds.put(phase, duration.toMillis() / 1000.0);
                metrics.phase(phase, duration);
            }
        }

        private TopicsRunException invariant(String message) {
            return new TopicsRunException(Code.INVARIANT, message);
        }
    }

    /** The assignment, and the prototypes it was built from: the gated known-Other ones are saved too. */
    private record Assigned(PrototypeAssigner assigner, int[] labels, List<float[]> topicPrototypes,
            List<Integer> owners, List<float[]> otherPrototypes) {
    }

    private static float[] centre(float[] centres, int group) {
        var centre = new float[DIMENSIONS];
        System.arraycopy(centres, group * DIMENSIONS, centre, 0, DIMENSIONS);
        return centre;
    }

    private static boolean isTimeout(InferenceException exception) {
        // A read timeout surfaces as a transport error whose cause chain holds the timeout.
        return exception.isOutOfTime()
                || ExceptionUtils.indexOfType(exception, SocketTimeoutException.class) >= 0
                || ExceptionUtils.indexOfType(exception, TimeoutException.class) >= 0;
    }
}
