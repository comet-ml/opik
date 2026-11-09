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
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.Uninterruptibles;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import reactor.util.retry.Retry;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
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
 * <p>The save commits one MySQL transaction first: the project's state, the new topics, its prototypes (replaced), the
 * run's counts ({@code topic_run_counts}) and the run as {@code saved}. Then it inserts {@code trace_topics}, retried up
 * to {@value #INSERT_RETRIES} times. A failure before the commit writes nothing but the failed run. An insert that still
 * fails after the commit leaves the run saved and the window's traces with the topics they had, until a later saved run
 * rewrites the window (the latest row per trace wins).
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicsRebuildService {

    static final int DIMENSIONS = TopicVectorsTickService.EMBED_DIMENSIONS;
    static final int MAX_TOPICS = 10;
    /** The first build's fine groups: one per {@value #TEXTS_PER_GROUP} unique texts, within these bounds. */
    static final int MIN_GROUPS = 30;
    static final int MAX_GROUPS = 50;
    static final int TEXTS_PER_GROUP = 30;
    /**
     * The author sees {@value #AUTHOR_FREQUENT} most frequent texts per group plus the ones nearest its centre, in all
     * clamp(round({@value #AUTHOR_BUDGET} / k), {@value #AUTHOR_MIN}, {@value #AUTHOR_MAX}) texts, so the prompt stays
     * about as long as the longest one known to work (5 texts on 50 groups).
     */
    static final int AUTHOR_FREQUENT = 5;
    static final int AUTHOR_BUDGET = 250;
    static final int AUTHOR_MIN = 5;
    static final int AUTHOR_MAX = 10;
    static final int RECENT_DAYS = 7;
    static final int QWEN_MAX_TOKENS = 2500;
    /** A first build over fewer traces is marked provisional in its stats. */
    static final int PROVISIONAL_BELOW = 1_000;
    static final double UNIT_TOLERANCE = 1e-3;
    /**
     * A run's ClickHouse read stops after this, so one slow read does not hold the server for the whole run; so does
     * each attempt of its {@code trace_topics} insert.
     */
    static final long READ_SECONDS = 120;
    /** The {@code trace_topics} insert after the commit: retries, and the first backoff (it doubles each time). */
    static final int INSERT_RETRIES = 3;
    static final Duration INSERT_BACKOFF = Duration.ofMillis(500);
    /** Failure codes whose exceptions can carry text of Qwen's answer. */
    private static final Set<Code> QWEN_CODES = EnumSet.of(Code.QWEN_TIMEOUT, Code.QWEN_ERROR, Code.QWEN_BAD_ANSWER);
    /** The longest the insert after the commit takes: every attempt to its stop, plus the backoffs and some slack. */
    static final Duration INSERT_BOUND = Duration.ofSeconds(READ_SECONDS * (INSERT_RETRIES + 1) + 30);

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

    /**
     * The first build's number of fine groups: min({@value #MAX_GROUPS}, max({@value #MIN_GROUPS}, unique /
     * {@value #TEXTS_PER_GROUP})), at most the unique texts.
     */
    static int groups(int unique) {
        return Math.min(Math.min(MAX_GROUPS, Math.max(MIN_GROUPS, unique / TEXTS_PER_GROUP)), unique);
    }

    /** ClickHouse {@code max_execution_time} of a run's read: {@value #READ_SECONDS} s, or the run's timeout if shorter. */
    static long readSeconds(@NonNull TopicsConfig.NightlyConfig nightly) {
        return Math.min(READ_SECONDS, nightly.getProjectTimeout().toSeconds());
    }

    /** How many texts the author sees per group for {@code k} groups. */
    static int authorExampleCount(int k) {
        return Math.min(AUTHOR_MAX, Math.max(AUTHOR_MIN, (int) Math.round((double) AUTHOR_BUDGET / k)));
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
     * and its timeout both. Counts the run in {@code topics.runs} only when this mark ends its row. A mark that throws
     * leaves the row {@code running} and counts nothing: the next night's housekeeping fails the row and counts it, as
     * {@code abandoned}, so every run is counted once, by the write that ends it.
     */
    public boolean fail(@NonNull UUID runId, @NonNull String workspaceId, @NonNull Code code, @NonNull String message,
            String stats) {
        int updated = template.inTransaction(WRITE,
                handle -> handle.attach(TopicRunDAO.class).markFailed(workspaceId, runId,
                        Instant.now(), code.getValue(), StringUtils.truncate(message, TopicRun.MAX_ERROR_MESSAGE),
                        stats));
        if (updated == 1) {
            metrics.run(workspaceId, TopicRun.Status.FAILED, code.getValue());
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
                    .findStates(workspaceId, List.of(projectId)).stream().findFirst());
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
            int k = groups(unique);
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
            int perGroup = authorExampleCount(k);
            stats.put("author_examples", perGroup);
            var examples = GroupSummaries.authorExamples(texts, vectors, groupOf, fit.centres(), DIMENSIONS,
                    Math.min(AUTHOR_FREQUENT, perGroup), Math.max(0, perGroup - AUTHOR_FREQUENT));
            var prompt = TopicPrompts.author(groups, MAX_TOPICS, examples);
            // The prompt holds users' trace text: the log gets its size and hash only, never the text.
            log.info("Topics run '{}' author prompt, project '{}', groups '{}', examples per group '{}', chars '{}', "
                    + "sha256 '{}'", runId, projectId, groups.size(), perGroup, prompt.length(), sha256(prompt));
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
            timed("save", () -> {
                save(rows, assigned.labels(), topics, prototypes);
                return null;
            });
        }

        private List<TopicWindowRow> readWindow() {
            List<TopicWindowRow> rows;
            try {
                rows = windowDAO.readWindow(workspaceId, projectId, from, to, config.getNightly().getMaxVectors(),
                        readSeconds(config.getNightly())).collectList().block();
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
                    metrics.qwen(workspaceId, "bad_answer");
                    throw new TopicsRunException(Code.QWEN_BAD_ANSWER,
                            "the Qwen answer breaks the schema, status '%s'".formatted(exception.getHttpStatus()),
                            exception);
                }
                boolean timeout = isTimeout(exception);
                metrics.qwen(workspaceId, timeout ? "timeout" : "error");
                throw new TopicsRunException(timeout ? Code.QWEN_TIMEOUT : Code.QWEN_ERROR,
                        "the Qwen call failed, code '%s', status '%s'".formatted(exception.getCode(),
                                exception.getHttpStatus()),
                        exception);
            }
            var output = response.result().output();
            log.info("Topics run '{}' author answer, project '{}', input tokens '{}', output tokens '{}', cache hit "
                    + "'{}'", runId, projectId, response.usage().inputTokens(), response.usage().outputTokens(),
                    response.cacheHit());
            // The answer names topics from users' trace text: the log gets its size and hash only, never the text.
            var canonical = output == null ? "null" : InferenceJson.canonical(output);
            log.info("Topics run '{}' author answer body, project '{}', chars '{}', sha256 '{}'", runId, projectId,
                    canonical.length(), sha256(canonical));
            stats.put("qwen_input_tokens", response.usage().inputTokens());
            stats.put("qwen_output_tokens", response.usage().outputTokens());
            stats.put("qwen_cache_hit", response.cacheHit());
            try {
                // Counted once the answer is read: an answer that cannot be read is bad_answer, not ok.
                var answer = AuthorAnswer.parse(output);
                metrics.qwen(workspaceId, "ok");
                return answer;
            } catch (TopicsRunException exception) {
                metrics.qwen(workspaceId, "bad_answer");
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
                if (StringUtils.isBlank(topic.name()) || AuthorApplier.isCatchAllName(topic.name())) {
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
         * Inserts the saved run's trace topics, retried up to {@value #INSERT_RETRIES} times: a repeat writes the same
         * rows, one per trace. Each attempt stops after {@value #READ_SECONDS} s, so a stalled one does not keep the
         * run's thread and window. The run is saved by now, so the insert goes on through an interrupt (a timeout that
         * came after the commit) and the interrupt is kept. False when it still failed.
         */
        private boolean insertTraceTopics(List<TraceTopic> traceTopics) {
            var insert = traceTopicsDAO.insert(traceTopics)
                    .timeout(Duration.ofSeconds(READ_SECONDS))
                    .retryWhen(Retry.backoff(INSERT_RETRIES, INSERT_BACKOFF)
                            .doBeforeRetry(signal -> log.warn("Topics run '{}' retries its trace_topics insert, "
                                    + "project '{}', attempt '{}'", runId, projectId, signal.totalRetries() + 2,
                                    signal.failure()))
                            .onRetryExhaustedThrow((spec, signal) -> signal.failure()));
            try {
                Uninterruptibles.getUninterruptibly(insert.toFuture());
                metrics.saved(workspaceId, "trace_topics", traceTopics.size());
                return true;
            } catch (ExecutionException | RuntimeException exception) {
                log.error("Topics run '{}' saved, but its trace_topics insert failed, project '{}'; the window's "
                        + "traces keep the topics they had until a later saved run", runId, projectId, exception);
                return false;
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
            long otherTraceCount = traceTopics.stream().filter(row -> TraceTopic.OTHER.equals(row.topicId())).count();
            try {
                var now = Instant.now();
                var counts = TopicRanking.runCounts(runId, workspaceId, projectId, topics, otherTraceCount, now);
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
                    topicDAO.deletePrototypes(workspaceId, projectId);
                    if (!prototypes.isEmpty()) {
                        topicDAO.insertPrototypes(prototypes);
                    }
                    var runDAO = handle.attach(TopicRunDAO.class);
                    runDAO.insertCounts(counts);
                    if (runDAO.markSaved(workspaceId, runId, now, statsJson) != 1) {
                        // Failed meanwhile, by its timeout: roll the save back.
                        throw new TopicsRunException(Code.TIMEOUT, "the run ended before its save committed");
                    }
                    return null;
                });
            } catch (TopicsRunException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                throw new TopicsRunException(Code.SAVE, "the save failed", exception);
            }
            metrics.run(workspaceId, TopicRun.Status.SAVED, null);
            metrics.saved(workspaceId, "topics", topics.size());
            metrics.saved(workspaceId, "topic_prototypes", prototypes.size());
            // The value the run saved in its stats, not computed again.
            metrics.otherShare(workspaceId, stats.path("other_share").asDouble());
            int inserted = insertTraceTopics(traceTopics) ? traceTopics.size() : 0;
            log.info("Topics run '{}' saved, project '{}', trace topics '{}', topics '{}', prototypes '{}'", runId,
                    projectId, inserted, topics.size(), prototypes.size());
        }

        Outcome failed(Code code, Exception exception) {
            stats.set("seconds", seconds);
            var message = StringUtils.defaultIfBlank(ExceptionUtils.getMessage(exception), code.getValue());
            if (QWEN_CODES.contains(code)) {
                // A cause of a Qwen failure can quote the answer (a Jackson parse error does): the exception types
                // only, never their messages or stack. The answer's length and SHA-256 are logged when it arrives.
                log.warn("Topics run '{}' failed, project '{}', code '{}', errors '{}'", runId, projectId,
                        code.getValue(), ExceptionUtils.getThrowableList(exception).stream()
                                .map(throwable -> throwable.getClass().getName()).toList());
            } else {
                log.warn("Topics run '{}' failed, project '{}', code '{}'", runId, projectId, code.getValue(),
                        exception);
            }
            try {
                fail(runId, workspaceId, code, message, stats.toString());
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
                metrics.phase(workspaceId, phase, duration);
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

    /** Lets a prompt or an answer be matched across logs (opik-inference's included) without logging its text. */
    private static String sha256(String text) {
        return Hashing.sha256().hashString(text, StandardCharsets.UTF_8).toString();
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
