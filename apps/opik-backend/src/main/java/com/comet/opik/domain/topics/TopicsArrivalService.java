package com.comet.opik.domain.topics;

import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.metrics.ErrorMetricsResolver;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.ERROR_TYPE_KEY;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * Topic on arrival: right after the vectors tick inserts a chunk of vectors, each trace gets the topic of its project's
 * saved list, as a {@code trace_topics} row with source {@code arrival} and no run id.
 *
 * <p>The rule is the algorithm's {@link TopicAlgorithm#labeller}, the same one the labelling pass uses, made from the
 * project's {@code topics} rows and the list's known-Other vectors ({@code topic_projects.other_vectors}). A tick reads
 * each project's list once, in one read-only MySQL transaction for the projects of a chunk. A project with no saved
 * list, or a list with no topic, has no labeller: its traces get no row and wait for the project's next run.
 *
 * <p>Every vector it gets has a non-blank text and {@link TopicEmbedding#DIMENSIONS} values, because the tick wrote
 * it: the tick embeds only a non-blank summary and keeps only vectors of that length, the length of a saved prototype.
 * So nothing is filtered before the labeller.
 *
 * <p>A run's row wins over an arrival row: readers take the latest row per trace by {@code last_updated_at}, and on an
 * equal time the row with {@code source = 'run'}.
 *
 * <p>Best effort: a failure is logged and counted, never returned as an error, and the tick writes and counts its
 * vectors as without arrival; the next run labels those traces. Any failure (the read, the insert, or anything else in
 * a step) stops arrival for the rest of the tick, so a failing MySQL or ClickHouse costs the tick at most one step's
 * time per workspace in flight. The traces of the later chunks count as {@value #AFTER_FAILURE}.
 *
 * <p>Bounded in time: the steps of one tick take {@code topics.arrival.timeout} in all, summed over the workspaces the
 * tick visits at once, and each one ends before the tick's deadline. A step with less than a second left does not
 * start; its traces count as {@value #OVER_BUDGET}. A read or insert that outlasts the time left fails with error type
 * {@code TimeoutException}.
 */
@Slf4j
@Singleton
public class TopicsArrivalService {

    static final String TOPIC = "topic";
    static final String OTHER = "other";
    static final String NO_PROTOTYPES = "no_prototypes";
    static final String OVER_BUDGET = "over_budget";
    static final String AFTER_FAILURE = "after_failure";

    static final String READ = "read";
    static final String ASSIGN = "assign";
    static final String INSERT = "insert";

    // A step that starts with almost no time would time out, and be counted and logged as a failure, when it only ran
    // out of budget. So a step starts only with at least this much left.
    private static final Duration MIN_TIME = Duration.ofSeconds(1);

    private final TopicsConfig config;
    private final TransactionTemplate template;
    private final TraceTopicsDAO traceTopicsDAO;
    private final TopicAlgorithm algorithm;
    private final WorkspaceNameService names;
    // The tick's clock: the time bound is measured against the tick's deadline.
    private final Clock clock;

    private final LongCounter labelled;
    private final LongCounter failures;
    private final DoubleHistogram duration;

    @Inject
    public TopicsArrivalService(@NonNull @Config("topics") TopicsConfig config, @NonNull TransactionTemplate template,
            @NonNull TraceTopicsDAO traceTopicsDAO, @NonNull TopicAlgorithm algorithm,
            @NonNull WorkspaceNameService names) {
        this(config, template, traceTopicsDAO, algorithm, names, GlobalOpenTelemetry.get().getMeter("opik.topics"),
                Clock.systemUTC());
    }

    TopicsArrivalService(@NonNull TopicsConfig config, @NonNull TransactionTemplate template,
            @NonNull TraceTopicsDAO traceTopicsDAO, @NonNull TopicAlgorithm algorithm,
            @NonNull WorkspaceNameService names, @NonNull Meter meter, @NonNull Clock clock) {
        this.config = config;
        this.template = template;
        this.traceTopicsDAO = traceTopicsDAO;
        this.algorithm = algorithm;
        this.names = names;
        this.clock = clock;
        this.labelled = meter.counterBuilder("topics.arrival.labelled")
                .setDescription("Traces the vectors tick labelled on arrival, by outcome and workspace: topic or other "
                        + "(a trace_topics row written), no_prototypes (the project has no saved list, or one with "
                        + "no topic), over_budget (no time left of topics.arrival.timeout or of the tick), after_failure "
                        + "(arrival stopped earlier in the tick)")
                .build();
        this.failures = meter.counterBuilder("topics.arrival.failures")
                .setDescription(
                        "Arrival steps that failed, by phase: read (the projects' saved lists in MySQL), insert "
                                + "(trace_topics), assign (any other failure of the step); with error type and workspace, "
                                + "TimeoutException when the step ran out of time. Arrival stops for the rest of the tick; the "
                                + "next run labels those traces")
                .build();
        this.duration = meter.histogramBuilder("topics.arrival.duration_seconds").setUnit("s")
                .setDescription("Wall time of the arrival step after one vectors insert, by workspace; steps that "
                        + "do not start are not recorded")
                .build();
    }

    /** One tick's arrival steps; a no-op when arrival is off. */
    public Tick tick() {
        return new Tick(config.getArrival().isEnabled());
    }

    /** The arrival steps of one tick. Safe to share between the workspaces the tick visits at once. */
    public final class Tick {

        private final boolean enabled;
        // Per project read this tick: its labeller, or empty when it has no saved list or one with no topic.
        private final Map<UUID, Optional<TopicLabeller>> labellers = new ConcurrentHashMap<>();
        // Set by any failure: arrival does nothing more this tick.
        private final AtomicBoolean stopped = new AtomicBoolean();
        private final AtomicLong spentNanos = new AtomicLong();

        private Tick(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * Labels the vectors one insert wrote, all of one workspace, and writes their {@code trace_topics} rows, within
         * what is left of {@code topics.arrival.timeout} for the tick and before {@code tickDeadline}. Completes empty,
         * and never with an error.
         */
        public Mono<Void> label(@NonNull String workspaceId, @NonNull List<TopicVector> vectors,
                @NonNull Instant tickDeadline) {
            if (!enabled || vectors.isEmpty()) {
                return Mono.empty();
            }
            return Mono.defer(() -> {
                var workspaceName = names.nameOrId(workspaceId);
                if (stopped.get()) {
                    count(workspaceId, workspaceName, AFTER_FAILURE, vectors.size());
                    return Mono.<Void>empty();
                }
                var now = clock.instant();
                var left = config.getArrival().getTimeout().toJavaDuration().minusNanos(spentNanos.get());
                var until = min(now.plus(left), tickDeadline);
                if (Duration.between(now, until).compareTo(MIN_TIME) < 0) {
                    count(workspaceId, workspaceName, OVER_BUDGET, vectors.size());
                    return Mono.<Void>empty();
                }
                long started = System.nanoTime();
                return Mono.defer(() -> labelChunk(workspaceId, workspaceName, vectors, until))
                        .onErrorResume(exception -> {
                            stop(workspaceId, workspaceName, ASSIGN, exception);
                            log.warn("Topic arrival failed, arrival stops for this tick, workspace '{}', vectors '{}'",
                                    workspaceId, vectors.size(), exception);
                            return Mono.empty();
                        })
                        .doFinally(signal -> {
                            long took = System.nanoTime() - started;
                            spentNanos.addAndGet(took);
                            duration.record(took / 1e9, TopicVectorsMetrics.workspace(workspaceId, workspaceName)
                                    .build());
                        });
            });
        }

        private Mono<Void> labelChunk(String workspaceId, String workspaceName, List<TopicVector> vectors,
                Instant until) {
            var byProject = vectors.stream().collect(Collectors.groupingBy(TopicVector::projectId,
                    LinkedHashMap::new, Collectors.toList()));
            return read(workspaceId, workspaceName, byProject.keySet(), until).flatMap(read -> {
                if (!read) {
                    return Mono.empty();
                }
                var rows = new ArrayList<TraceTopic>(vectors.size());
                int topics = 0;
                int other = 0;
                for (var entry : byProject.entrySet()) {
                    var group = entry.getValue();
                    var labeller = labellers.get(entry.getKey());
                    if (labeller.isEmpty()) {
                        count(workspaceId, workspaceName, NO_PROTOTYPES, group.size());
                        continue;
                    }
                    var topicIds = labeller.get().label(flatten(group));
                    for (int i = 0; i < group.size(); i++) {
                        var vector = group.get(i);
                        rows.add(TraceTopic.builder()
                                .workspaceId(vector.workspaceId())
                                .projectId(vector.projectId())
                                .traceId(vector.traceId())
                                .traceCreatedAt(vector.traceCreatedAt())
                                .topicId(topicIds[i])
                                .source(TraceTopic.Source.ARRIVAL)
                                .runId(null)
                                .build());
                        if (TraceTopic.OTHER.equals(topicIds[i])) {
                            other++;
                        } else {
                            topics++;
                        }
                    }
                }
                return insert(workspaceId, workspaceName, rows, topics, other, until);
            });
        }

        /**
         * Reads the saved lists of the projects this tick has not read yet, in one read-only transaction, and asks the
         * algorithm for each project's labeller. Stops waiting at {@code until}. A failed read stops arrival for the
         * tick and gives false.
         */
        private Mono<Boolean> read(String workspaceId, String workspaceName, Set<UUID> projectIds, Instant until) {
            var toRead = projectIds.stream().filter(projectId -> !labellers.containsKey(projectId)).toList();
            if (toRead.isEmpty()) {
                return Mono.just(true);
            }
            return Mono.fromCallable(() -> template.inTransaction(READ_ONLY, handle -> {
                var dao = handle.attach(TopicDAO.class);
                return new SavedLists(dao.findProjects(workspaceId, toRead), dao.findByProjects(workspaceId, toRead));
            }))
                    .subscribeOn(Schedulers.boundedElastic())
                    .timeout(left(until))
                    .map(Optional::of)
                    .onErrorResume(exception -> {
                        stop(workspaceId, workspaceName, READ, exception);
                        log.warn("Topic arrival read failed, arrival stops for this tick, workspace '{}', projects "
                                + "'{}'", workspaceId, toRead.size(), exception);
                        return Mono.just(Optional.empty());
                    })
                    .map(saved -> {
                        if (saved.isEmpty()) {
                            return false;
                        }
                        var projects = saved.get().projects().stream()
                                .collect(Collectors.toMap(TopicProject::projectId, Function.identity()));
                        // In (born, id) order within each project: the query's order, which grouping keeps.
                        var topics = saved.get().topics().stream().collect(Collectors.groupingBy(Topic::projectId));
                        for (var projectId : toRead) {
                            var project = projects.get(projectId);
                            labellers.put(projectId, project == null || project.listRunId() == null
                                    ? Optional.empty()
                                    : algorithm.labeller(topics.getOrDefault(projectId, List.of()),
                                            project.otherVectors()));
                        }
                        return true;
                    });
        }

        /**
         * The rows are built: they are inserted with whatever time is left, and count as over_budget only when none
         * is.
         */
        private Mono<Void> insert(String workspaceId, String workspaceName, List<TraceTopic> rows, int topics,
                int other, Instant until) {
            if (rows.isEmpty()) {
                return Mono.empty();
            }
            var left = left(until);
            if (!left.isPositive()) {
                count(workspaceId, workspaceName, OVER_BUDGET, rows.size());
                return Mono.empty();
            }
            return Mono.defer(() -> traceTopicsDAO.insert(rows))
                    .timeout(left)
                    .doOnSuccess(inserted -> {
                        count(workspaceId, workspaceName, TOPIC, topics);
                        count(workspaceId, workspaceName, OTHER, other);
                    })
                    .onErrorResume(exception -> {
                        stop(workspaceId, workspaceName, INSERT, exception);
                        log.warn("Topic arrival insert failed, arrival stops for this tick, workspace '{}', rows '{}'",
                                workspaceId, rows.size(), exception);
                        return Mono.empty();
                    })
                    .then();
        }

        private void stop(String workspaceId, String workspaceName, String phase, Throwable error) {
            stopped.set(true);
            failures.add(1, attributes(workspaceId, workspaceName)
                    .put(stringKey("phase"), phase)
                    .put(ERROR_TYPE_KEY, ErrorMetricsResolver.errorType(error))
                    .build());
        }
    }

    private record SavedLists(List<TopicProject> projects, List<Topic> topics) {
    }

    private void count(String workspaceId, String workspaceName, String outcome, int traces) {
        if (traces > 0) {
            labelled.add(traces, attributes(workspaceId, workspaceName).put(stringKey("outcome"), outcome).build());
        }
    }

    private static AttributesBuilder attributes(String workspaceId, String workspaceName) {
        return TopicVectorsMetrics.workspace(workspaceId, workspaceName);
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private Duration left(Instant until) {
        var left = Duration.between(clock.instant(), until);
        return left.isNegative() ? Duration.ZERO : left;
    }

    /** The vectors' values, one vector after another, as the labeller takes them. */
    private static float[] flatten(List<TopicVector> vectors) {
        int dimensions = TopicEmbedding.DIMENSIONS;
        var out = new float[vectors.size() * dimensions];
        for (int r = 0; r < vectors.size(); r++) {
            System.arraycopy(vectors.get(r).vector(), 0, out, r * dimensions, dimensions);
        }
        return out;
    }
}
