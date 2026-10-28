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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/**
 * One project's run of the nightly job, in one of two modes. The first build (stage 2a), for a project without live
 * topics: read the window of vectors, merge identical texts, k-means them into fine groups, have Qwen name topics over
 * the groups, assign every window trace to its nearest prototype, check the invariants and save. The nightly update
 * (stage 2b), for a project with live topics: read the window and the saved list, run {@link NightlyUpdate}, compute
 * the churn against last night's labels, check {@link NightInvariants} and save. Synchronous: the nightly job calls it
 * on its own thread, one project at a time.
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
    static final String AUTHOR_KIND = "author";
    /** Stage 2b D5: the revise deadline is {@code topics.nightly.qwenDeadline}. */
    static final Duration VERIFY_DEADLINE = Duration.ofSeconds(60);
    static final Duration NAMING_DEADLINE = Duration.ofSeconds(30);

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

    /** Runs {@code mode}: {@link #firstBuild} or {@link #nightlyUpdate}. */
    public Outcome run(@NonNull UUID runId, @NonNull String workspaceId, @NonNull UUID projectId,
            @NonNull LocalDate night, @NonNull TopicRun.Mode mode) {
        return mode == TopicRun.Mode.FIRST_BUILD
                ? firstBuild(runId, workspaceId, projectId, night)
                : nightlyUpdate(runId, workspaceId, projectId, night);
    }

    /**
     * Updates the project's topic list for {@code night}. Never throws: a failure is recorded on the run and returned,
     * and the previous night's list stays.
     */
    public Outcome nightlyUpdate(@NonNull UUID runId, @NonNull String workspaceId, @NonNull UUID projectId,
            @NonNull LocalDate night) {
        var run = new Run(runId, workspaceId, projectId, night);
        try {
            run.update();
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
            var prototypes = prototypes(assigned.assigner(), topics);
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
                    + "k '{}', seed '{}', topics '{}', Other share '{}', seconds '{}'", runId, projectId, from, to, n,
                    unique, k, seed, topics.stream().map(topic -> topic.name() + " (" + topic.size() + ")").toList(),
                    TopicText.fixed((double) other / n, 3), seconds);

            timed("save", () -> {
                save(rows, assigned.labels(), topics, prototypes);
                return null;
            });
        }

        void update() {
            stats.put("mode", TopicRun.Mode.NIGHTLY_UPDATE.getValue());
            var rows = timed("window", this::readWindow);
            int n = rows.size();
            var texts = new String[n];
            var vectors = new float[n * DIMENSIONS];
            var createdAt = new Instant[n];
            var traceIds = new UUID[n];
            var versions = new TreeMap<Long, Integer>();
            for (int i = 0; i < n; i++) {
                var row = rows.get(i);
                texts[i] = row.text();
                System.arraycopy(row.vector(), 0, vectors, i * DIMENSIONS, DIMENSIONS);
                createdAt[i] = row.traceCreatedAt();
                traceIds[i] = row.traceId();
                versions.merge(row.taskVersion(), 1, Integer::sum);
            }
            var versionShares = stats.putObject("task_version_shares");
            versions.forEach((version, count) -> versionShares.put(String.valueOf(version), (double) count / n));
            stats.put("vectors", n);

            var saved = timed("state", this::readState);
            var state = saved.state();
            stats.put("rebuilds_before", state.getRebuilds());
            stats.put("topics_before", state.liveCount());
            var window = NightWindow.builder().texts(texts).vectors(vectors).createdAt(createdAt).from(from)
                    .night(to).dimensions(DIMENSIONS).build();

            var result = timed("update", () -> NightlyUpdate.run(state, window, MAX_TOPICS,
                    NightClusterer.KMEANS, this::generate));
            NightInvariants.check(state, saved.names(), saved.retired(), result.labels(), n, MAX_TOPICS,
                    DIMENSIONS);

            var labels = new UUID[n];
            int other = 0;
            for (int i = 0; i < n; i++) {
                int label = result.labels()[i];
                other += label == PrototypeAssigner.OTHER ? 1 : 0;
                labels[i] = label == PrototypeAssigner.OTHER ? TraceTopic.OTHER : state.topic(label).getId();
            }
            var previous = timed("churn", () -> readPreviousLabels(saved.projectState().lastRunId()));
            var churn = TopicChurn.of(previous, traceIds, labels);

            stats.put("review_night", result.reviewNight());
            stats.put("review", result.review());
            stats.put("unassigned", result.unassigned());
            stats.put("candidates", result.candidates().size());
            stats.put("big", result.big().size());
            if (result.verified() != null) {
                stats.put("verify_pairs", result.verified().pairs().size());
                stats.put("verify_failed", result.verified().failed());
            }
            if (result.forced() != null) {
                var forced = stats.putObject("forced");
                result.forced().forEach(outcome -> forced.put(outcome.result().name().toLowerCase(Locale.ROOT),
                        forced.path(outcome.result().name().toLowerCase(Locale.ROOT)).asInt() + 1));
            }
            var born = stats.putArray("born");
            var retired = stats.putObject("retired");
            for (var topic : state.getTopics()) {
                if (!saved.names().containsKey(topic.getId())) {
                    born.add(topic.getName());
                }
                if (!topic.isLive()) {
                    var reason = topic.getRetiredReason().value();
                    retired.put(reason, retired.path(reason).asInt() + 1);
                }
            }
            stats.put("topics", state.liveCount());
            stats.put("other_share", (double) other / n);
            stats.put("other_prototypes", state.getOtherPrototypes().size());
            stats.put("churn", churn == null ? null : churn.churn());
            stats.put("churn_any", churn == null ? null : churn.churnAny());
            stats.set("seconds", seconds);
            log.info("Topics run '{}' updated, project '{}', window ['{}', '{}'), vectors '{}', review '{}', "
                    + "unassigned '{}', candidates '{}', big '{}', born '{}', retired '{}', topics '{}', Other share "
                    + "'{}', churn '{}', seconds '{}'", runId, projectId, from, to, n, result.review(),
                    result.unassigned(), result.candidates().size(), result.big().size(), born, retired,
                    state.liveCount(), TopicText.fixed((double) other / n, 3), churn, seconds);

            timed("save", () -> {
                saveUpdate(rows, labels, state, saved, born.size());
                return null;
            });
            if (churn != null) {
                metrics.churn(churn);
            }
        }

        /** The saved list as the night reads it: live topics ordered by born, then id (creation). */
        private SavedList readState() {
            return template.inTransaction(READ_ONLY, handle -> {
                var dao = handle.attach(TopicDAO.class);
                var states = dao.findStates(Set.of(projectId));
                var live = dao.findLive(projectId);
                if (states.isEmpty() || live.isEmpty()) {
                    throw new TopicsRunException(Code.INTERNAL, "the project has no saved topic list");
                }
                var byTopic = new LinkedHashMap<UUID, List<float[]>>();
                live.forEach(topic -> byTopic.put(topic.id(), new ArrayList<>()));
                var other = new ArrayList<float[]>();
                for (var prototype : dao.findPrototypes(projectId)) {
                    if (prototype.topicId() == null) {
                        other.add(prototype.values());
                    } else if (byTopic.containsKey(prototype.topicId())) {
                        byTopic.get(prototype.topicId()).add(prototype.values());
                    }
                }
                var topics = new ArrayList<NightState.Topic>();
                var names = new HashMap<UUID, String>();
                for (var topic : live) {
                    names.put(topic.id(), topic.name());
                    topics.add(NightState.Topic.builder()
                            .id(topic.id())
                            .name(topic.name())
                            .definition(topic.definition())
                            .born(topic.born())
                            .prototypes(byTopic.get(topic.id()))
                            .lowNights(topic.lowNights())
                            .lo7(topic.lo7())
                            .hi7(topic.hi7())
                            .inactiveSince(topic.inactiveSince())
                            .size(topic.size())
                            .build());
                }
                var state = NightState.builder()
                        .topics(topics)
                        .otherPrototypes(other)
                        .rebuilds(states.getFirst().rebuilds())
                        .firstBuild(LocalDate.ofInstant(states.getFirst().firstBuiltAt(), ZoneOffset.UTC))
                        .night(night)
                        .ids(idGenerator::generateId)
                        .build();
                return new SavedList(state, states.getFirst(), names, dao.findRetiredIds(projectId));
            });
        }

        /** D6: last saved night's label of each window trace, before tonight's are written. */
        private Map<UUID, UUID> readPreviousLabels(UUID lastRunId) {
            if (lastRunId == null) {
                return Map.of();
            }
            try {
                return traceTopicsDAO.findLabels(workspaceId, projectId, from, to, lastRunId).block();
            } catch (RuntimeException exception) {
                throw new TopicsRunException(Code.WINDOW_READ, "the previous labels read failed", exception);
            }
        }

        /**
         * {@link NightLlm} for the nightly update: the revise deadline is {@code qwenDeadline}, verify and naming have
         * their own (D5). A failed call throws and fails the run, whichever call it is; the project keeps yesterday's
         * list.
         */
        private JsonNode generate(String tag, String prompt, JsonNode schema, int maxTokens) {
            var kind = switch (tag) {
                case ForcedBirth.NAMING_TAG, ForcedBirth.NAMING_NEW_TAG -> "naming";
                case ForcedBirth.VERIFY_TAG -> "verify";
                default -> tag;
            };
            var deadline = switch (kind) {
                case "verify" -> VERIFY_DEADLINE;
                case "naming" -> NAMING_DEADLINE;
                default -> config.getNightly().getQwenDeadline().toJavaDuration();
            };
            // The prompt holds trace-derived text: DEBUG only.
            log.debug("Topics run '{}' {} prompt, project '{}':\n{}", runId, tag, projectId, prompt);
            var output = call(kind, runId + ":" + tag, prompt, schema, maxTokens, deadline);
            metrics.qwen(kind, "ok");
            return output == null ? NullNode.getInstance() : output;
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
            var output = call(AUTHOR_KIND, runId.toString(), prompt, TopicPrompts.AUTHOR_SCHEMA, QWEN_MAX_TOKENS,
                    config.getNightly().getQwenDeadline().toJavaDuration());
            try {
                var answer = AuthorAnswer.parse(output);
                metrics.qwen(AUTHOR_KIND, "ok");
                return answer;
            } catch (TopicsRunException exception) {
                metrics.qwen(AUTHOR_KIND, "bad_answer");
                throw exception;
            }
        }

        /**
         * One Qwen call on the batch lane: logs the answer, adds the call to the stats ({@code qwen_calls}, the token
         * sums, one {@code qwen} entry per call) and returns the output. A failed call is counted and thrown as
         * {@code qwen_timeout} or {@code qwen_error}.
         */
        private JsonNode call(String kind, String requestId, String prompt, JsonNode schema, int maxTokens,
                Duration deadline) {
            var request = GenerateRequest.builder()
                    .orgId(workspaceId)
                    .workspaceId(workspaceId)
                    .requestId(requestId)
                    .cache(true)
                    .lane(GenerateRequest.Lane.BATCH)
                    .messages(List.of(Message.builder().role(Message.Role.USER).content(prompt).build()))
                    .schema(schema)
                    .maxTokens(maxTokens)
                    .temperature(0)
                    .build();
            stats.put("qwen_calls", stats.path("qwen_calls").asInt() + 1);
            var entry = (stats.has("qwen") ? (ArrayNode) stats.get("qwen") : stats.putArray("qwen")).addObject();
            entry.put("kind", kind);
            GenerateResponse response;
            try {
                response = client.generate(request, Instant.now().plus(deadline)).block();
            } catch (InferenceException exception) {
                boolean timeout = isTimeout(exception);
                entry.put("outcome", timeout ? "timeout" : "error");
                metrics.qwen(kind, timeout ? "timeout" : "error");
                throw new TopicsRunException(timeout ? Code.QWEN_TIMEOUT : Code.QWEN_ERROR,
                        "the Qwen %s call failed, code '%s', status '%s'".formatted(kind, exception.getCode(),
                                exception.getHttpStatus()),
                        exception);
            } catch (RuntimeException exception) {
                // Not an unreadable answer: the call itself failed (a bad request, an interrupt).
                entry.put("outcome", "error");
                metrics.qwen(kind, "error");
                throw new TopicsRunException(Code.QWEN_ERROR, "the Qwen %s call failed".formatted(kind), exception);
            }
            if (response == null || response.result() == null || response.usage() == null) {
                entry.put("outcome", "error");
                metrics.qwen(kind, "error");
                throw new TopicsRunException(Code.QWEN_ERROR,
                        "the Qwen %s call answered without a result or usage".formatted(kind));
            }
            var output = response.result().output();
            log.info("Topics run '{}' {} answer, project '{}', input tokens '{}', output tokens '{}', cache hit "
                    + "'{}'", runId, kind, projectId, response.usage().inputTokens(),
                    response.usage().outputTokens(), response.cacheHit());
            // The answer holds trace-derived text: DEBUG only.
            log.debug("Topics run '{}' {} answer, project '{}':\n{}", runId, kind, projectId,
                    output == null ? "null" : InferenceJson.canonical(output));
            entry.put("outcome", "ok");
            entry.put("input_tokens", response.usage().inputTokens());
            entry.put("output_tokens", response.usage().outputTokens());
            entry.put("cache_hit", response.cacheHit());
            stats.put("qwen_input_tokens", stats.path("qwen_input_tokens").asLong() + response.usage().inputTokens());
            stats.put("qwen_output_tokens",
                    stats.path("qwen_output_tokens").asLong() + response.usage().outputTokens());
            stats.put("qwen_cache_hit", response.cacheHit());
            return output;
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
            return new Assigned(assigner, assigner.assign(vectors));
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

        private List<TopicPrototype> prototypes(PrototypeAssigner assigner, List<Topic> topics) {
            var prototypes = new ArrayList<TopicPrototype>(assigner.prototypeCount());
            for (int p = 0; p < assigner.prototypeCount(); p++) {
                int owner = assigner.owner(p);
                prototypes.add(TopicPrototype.builder()
                        .id(idGenerator.generateId())
                        .workspaceId(workspaceId)
                        .projectId(projectId)
                        .position(p)
                        .topicId(owner == PrototypeAssigner.OTHER ? null : topics.get(owner).id())
                        .vector(TopicPrototype.encode(assigner.prototype(p)))
                        .build());
            }
            return prototypes;
        }

        /** D5: what must hold before anything is written. */
        private void checkInvariants(List<Topic> topics, List<TopicPrototype> prototypes, int[] labels, int n) {
            if (topics.size() > MAX_TOPICS) {
                throw invariant("'%s' topics, at most '%s'".formatted(topics.size(), MAX_TOPICS));
            }
            for (var topic : topics) {
                if (StringUtils.isBlank(topic.name()) || AuthorApplier.isCatchAll(topic.name())) {
                    throw invariant("topic name '%s' is blank or a catch-all".formatted(topic.name()));
                }
                if (!topic.born().equals(night)) {
                    throw invariant("topic '%s' born '%s', not tonight".formatted(topic.name(), topic.born()));
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
         * Deletes the trace_topics rows of a run whose MySQL save did not commit, so the latest row of each trace is
         * the last saved run's again. Best effort: when the delete fails, or a merge already replaced the saved run's
         * row, the trace keeps this run's row, or none, until the next saved night writes it again.
         */
        private void takeBack(boolean inserted) {
            if (!inserted) {
                return;
            }
            try {
                traceTopicsDAO.deleteByRunId(workspaceId, projectId, runId).block();
            } catch (RuntimeException exception) {
                log.warn("Topics run '{}' could not delete its trace_topics rows after a failed save, project '{}'",
                        runId, projectId, exception);
            }
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
            boolean inserted = false;
            try {
                traceTopicsDAO.insert(traceTopics).block();
                inserted = true;
                var now = Instant.now();
                template.inTransaction(WRITE, handle -> {
                    var topicDAO = handle.attach(TopicDAO.class);
                    if (!topics.isEmpty()) {
                        topicDAO.insertTopics(topics);
                    }
                    topicDAO.deletePrototypes(projectId);
                    if (!prototypes.isEmpty()) {
                        topicDAO.insertPrototypes(prototypes);
                    }
                    topicDAO.upsertState(TopicProjectState.builder()
                            .projectId(projectId)
                            .workspaceId(workspaceId)
                            .rebuilds(1)
                            // The night, not the save time: a run by hand may build an earlier night's window.
                            .firstBuiltAt(to)
                            .lastRunId(runId)
                            .lastSuccessAt(now)
                            .windowUntil(to)
                            .build());
                    if (handle.attach(TopicRunDAO.class).markSaved(runId, now, statsJson) != 1) {
                        // Failed meanwhile, by its timeout: roll the save back.
                        throw new TopicsRunException(Code.TIMEOUT, "the run ended before its save committed");
                    }
                    return null;
                });
            } catch (TopicsRunException exception) {
                takeBack(inserted);
                throw exception;
            } catch (RuntimeException exception) {
                takeBack(inserted);
                throw new TopicsRunException(Code.SAVE, "the save failed", exception);
            }
            metrics.run(TopicRun.Status.SAVED, null);
            metrics.saved("trace_topics", traceTopics.size());
            metrics.saved("topics", topics.size());
            metrics.saved("topic_prototypes", prototypes.size());
            metrics.topics(TopicRun.Mode.FIRST_BUILD, topics.size(), topics.size());
            log.info("Topics run '{}' saved, project '{}', trace topics '{}', topics '{}', prototypes '{}'", runId,
                    projectId, traceTopics.size(), topics.size(), prototypes.size());
        }

        /**
         * As {@link #save}: {@code trace_topics} first, then one MySQL transaction: tonight's new topics inserted, the
         * ones read updated (definition, lifecycle columns, size, retirement), the prototypes replaced (live topics in
         * list order, then known Other), the state with {@code rebuilds} + 1, the run saved.
         */
        private void saveUpdate(List<TopicWindowRow> rows, UUID[] labels, NightState state, SavedList saved,
                int bornTonight) {
            var traceTopics = new ArrayList<TraceTopic>(rows.size());
            for (int i = 0; i < rows.size(); i++) {
                traceTopics.add(TraceTopic.builder()
                        .workspaceId(workspaceId)
                        .projectId(projectId)
                        .traceId(rows.get(i).traceId())
                        .traceCreatedAt(rows.get(i).traceCreatedAt())
                        .topicId(labels[i])
                        .source(TraceTopic.Source.NIGHT)
                        .runId(runId.toString())
                        .build());
            }
            var inserted = new ArrayList<Topic>();
            var updated = new ArrayList<Topic>();
            for (var topic : state.getTopics()) {
                var row = Topic.builder()
                        .id(topic.getId())
                        .workspaceId(workspaceId)
                        .projectId(projectId)
                        .name(topic.getName())
                        .definition(topic.getDefinition())
                        .born(topic.getBorn())
                        .retiredAt(topic.getRetiredAt())
                        .retiredReason(topic.getRetiredReason() == null ? null : topic.getRetiredReason().value())
                        .inactiveSince(topic.getInactiveSince())
                        .lowNights(topic.getLowNights())
                        .lo7(topic.getLo7())
                        .hi7(topic.getHi7())
                        .size(topic.getSize())
                        .build();
                (saved.names().containsKey(topic.getId()) ? updated : inserted).add(row);
            }
            var prototypes = new ArrayList<TopicPrototype>();
            for (int t : state.live()) {
                for (var vector : state.topic(t).getPrototypes()) {
                    prototypes.add(prototype(prototypes.size(), state.topic(t).getId(), vector));
                }
            }
            for (var vector : state.getOtherPrototypes()) {
                prototypes.add(prototype(prototypes.size(), null, vector));
            }
            var statsJson = stats.toString();
            boolean written = false;
            try {
                traceTopicsDAO.insert(traceTopics).block();
                written = true;
                var now = Instant.now();
                template.inTransaction(WRITE, handle -> {
                    var topicDAO = handle.attach(TopicDAO.class);
                    if (!inserted.isEmpty()) {
                        topicDAO.insertTopics(inserted);
                    }
                    if (!updated.isEmpty()) {
                        topicDAO.updateTopics(updated);
                    }
                    topicDAO.deletePrototypes(projectId);
                    if (!prototypes.isEmpty()) {
                        topicDAO.insertPrototypes(prototypes);
                    }
                    topicDAO.upsertState(saved.projectState().toBuilder()
                            .rebuilds(state.getRebuilds() + 1)
                            .lastRunId(runId)
                            .lastSuccessAt(now)
                            .windowUntil(to)
                            .build());
                    if (handle.attach(TopicRunDAO.class).markSaved(runId, now, statsJson) != 1) {
                        throw new TopicsRunException(Code.TIMEOUT, "the run ended before its save committed");
                    }
                    return null;
                });
            } catch (TopicsRunException exception) {
                takeBack(written);
                throw exception;
            } catch (RuntimeException exception) {
                takeBack(written);
                throw new TopicsRunException(Code.SAVE, "the save failed", exception);
            }
            metrics.run(TopicRun.Status.SAVED, null);
            metrics.saved("trace_topics", traceTopics.size());
            metrics.saved("topics", inserted.size() + updated.size());
            metrics.saved("topic_prototypes", prototypes.size());
            metrics.topics(TopicRun.Mode.NIGHTLY_UPDATE, state.liveCount(), bornTonight);
            state.getTopics().stream().filter(topic -> !topic.isLive())
                    .forEach(topic -> metrics.retired(topic.getRetiredReason().value()));
            log.info("Topics run '{}' saved, project '{}', trace topics '{}', topics inserted '{}', updated '{}', "
                    + "prototypes '{}'", runId, projectId, traceTopics.size(), inserted.size(), updated.size(),
                    prototypes.size());
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

    private record Assigned(PrototypeAssigner assigner, int[] labels) {
    }

    /** The list a nightly update read: its state, the project's row, the names read by id, the ids retired before. */
    private record SavedList(NightState state, TopicProjectState projectState, Map<UUID, String> names,
            List<UUID> retired) {
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
