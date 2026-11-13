package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicAlgorithm.Labeller;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.metrics.ErrorMetricsResolver;
import io.opentelemetry.api.GlobalOpenTelemetry;
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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.ERROR_TYPE_KEY;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * Topic on arrival: right after the vectors tick inserts a chunk of vectors, each trace gets the topic of the nearest
 * saved prototype of its project, as a {@code trace_topics} row with source {@code arrival} and an empty run id.
 *
 * <p>The rule is the algorithm's {@link TopicAlgorithm#labeller}, the same one the labelling pass uses. This service
 * reads each project's prototype rows once per tick, in one MySQL query for the projects of a chunk, bounds the time,
 * writes the rows and counts. A project with no prototype row, or with known-Other rows only, has no labeller: its
 * traces get no row and wait for the project's next run.
 *
 * <p>Every vector it gets has a non-blank text and {@value TopicVectorsTickService#EMBED_DIMENSIONS} values, because
 * the tick wrote it: the tick embeds only a non-blank summary and keeps only vectors of that length, the length of a
 * saved prototype. So nothing is filtered before the labeller.
 *
 * <p>The empty run id makes a run's row win: readers take the latest row per trace by {@code last_updated_at}, then
 * {@code run_id}, so on an equal time a run's row comes before an arrival row.
 *
 * <p>Best effort: a failure is logged and counted, never thrown, and the tick writes and counts its vectors as without
 * arrival; the next run labels those traces. Any failure (the read, the insert, or anything else in a step) stops
 * arrival for the rest of the tick, so a failing MySQL or ClickHouse costs the tick at most one step's time. The traces
 * of the later chunks count as {@value #AFTER_FAILURE}. An interrupt is not a failure: it is thrown, as the tick does
 * ({@link TopicVectorsTickService#rethrowIfInterrupted}), so a shutdown stops the tick.
 *
 * <p>Bounded in time: all the steps of one tick together end within {@code topics.arrival.timeout}, and each one before
 * the tick's deadline. A step with less than a second left does not start; its traces count as {@value #OVER_BUDGET}.
 * A read or insert that outlasts the time left fails with error type {@code TimeoutException}.
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

    private final LongCounter labelled;
    private final LongCounter failures;
    private final DoubleHistogram duration;

    @Inject
    public TopicsArrivalService(@NonNull @Config("topics") TopicsConfig config, @NonNull TransactionTemplate template,
            @NonNull TraceTopicsDAO traceTopicsDAO, @NonNull TopicAlgorithm algorithm) {
        this(config, template, traceTopicsDAO, algorithm, GlobalOpenTelemetry.get().getMeter("opik.topics"));
    }

    TopicsArrivalService(@NonNull TopicsConfig config, @NonNull TransactionTemplate template,
            @NonNull TraceTopicsDAO traceTopicsDAO, @NonNull TopicAlgorithm algorithm, @NonNull Meter meter) {
        this.config = config;
        this.template = template;
        this.traceTopicsDAO = traceTopicsDAO;
        this.algorithm = algorithm;
        this.labelled = meter.counterBuilder("topics.arrival.labelled")
                .setDescription("Traces the vectors tick labelled on arrival, by outcome and workspace: topic or other "
                        + "(a trace_topics row written), no_prototypes (the project's saved list labels nothing), "
                        + "over_budget (no time left of topics.arrival.timeout or of the tick), after_failure (arrival "
                        + "stopped earlier in the tick)")
                .build();
        this.failures = meter.counterBuilder("topics.arrival.failures")
                .setDescription("Arrival steps that failed, by phase: read (the projects' prototypes in MySQL), insert "
                        + "(trace_topics), assign (any other failure of the step); with error type and workspace, "
                        + "TimeoutException when the step ran out of time. Arrival stops for the rest of the tick; the "
                        + "next run labels those traces")
                .build();
        this.duration = meter.histogramBuilder("topics.arrival.duration_seconds").setUnit("s")
                .setDescription("Wall time of the arrival step after one vectors insert, by workspace; steps that "
                        + "do not start are not recorded")
                .build();
    }

    /** One tick's arrival steps, which read each project's prototypes once; a no-op when arrival is off. */
    public Tick tick() {
        return new Tick(config.getArrival().isEnabled());
    }

    /** The arrival steps of one tick. Not thread safe: the tick is one thread. */
    public final class Tick {

        private final boolean enabled;
        // Per project read this tick: its labeller, or empty when its saved list labels nothing.
        private final Map<UUID, Optional<Labeller>> labellers = new HashMap<>();
        // Set by any failure: arrival does nothing more this tick.
        private boolean stopped;
        // The time the tick's steps took so far: all of them together end within topics.arrival.timeout.
        private Duration spent = Duration.ZERO;

        private Tick(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * Labels the vectors one insert wrote, all of one workspace, and writes their {@code trace_topics} rows, within
         * what is left of {@code topics.arrival.timeout} for the tick and before {@code tickDeadline}. Throws only on an
         * interrupt.
         *
         * @param workspaceName the name the tick resolved, for the metrics: this step looks up nothing
         * @param tickDeadline  the end of the tick's budget
         */
        public void label(@NonNull String workspaceId, @NonNull String workspaceName,
                @NonNull List<TopicVector> vectors, @NonNull Instant tickDeadline) {
            if (!enabled || vectors.isEmpty()) {
                return;
            }
            if (stopped) {
                count(workspaceId, workspaceName, AFTER_FAILURE, vectors.size());
                return;
            }
            var now = Instant.now();
            var until = min(now.plus(config.getArrival().getTimeout().toJavaDuration().minus(spent)), tickDeadline);
            if (Duration.between(now, until).compareTo(MIN_TIME) < 0) {
                count(workspaceId, workspaceName, OVER_BUDGET, vectors.size());
                return;
            }
            long started = System.nanoTime();
            try {
                labelChunk(workspaceId, workspaceName, vectors, until);
            } catch (RuntimeException exception) {
                TopicVectorsTickService.rethrowIfInterrupted(exception);
                stop(workspaceId, workspaceName, ASSIGN, exception);
                log.warn("Topic arrival failed, arrival stops for this tick, workspace '{}', vectors '{}'", workspaceId,
                        vectors.size(), exception);
            } finally {
                long took = System.nanoTime() - started;
                spent = spent.plusNanos(took);
                duration.record(took / 1e9, TopicsWorkspaceNames.builder(workspaceId, workspaceName).build());
            }
        }

        private void labelChunk(String workspaceId, String workspaceName, List<TopicVector> vectors, Instant until) {
            var byProject = vectors.stream().collect(Collectors.groupingBy(TopicVector::projectId,
                    LinkedHashMap::new, Collectors.toList()));
            if (!read(workspaceId, workspaceName, byProject.keySet(), until)) {
                return;
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
                            .runId("")
                            .build());
                    if (TraceTopic.OTHER.equals(topicIds[i])) {
                        other++;
                    } else {
                        topics++;
                    }
                }
            }
            if (rows.isEmpty()) {
                return;
            }
            // The rows are built: they are inserted with whatever time is left, and count as over_budget only when none
            // is.
            var left = left(until);
            if (!left.isPositive()) {
                count(workspaceId, workspaceName, OVER_BUDGET, rows.size());
                return;
            }
            try {
                traceTopicsDAO.insert(rows).timeout(left).block();
            } catch (RuntimeException exception) {
                TopicVectorsTickService.rethrowIfInterrupted(exception);
                stop(workspaceId, workspaceName, INSERT, exception);
                log.warn("Topic arrival insert failed, arrival stops for this tick, workspace '{}', rows '{}'",
                        workspaceId, rows.size(), exception);
                return;
            }
            count(workspaceId, workspaceName, TOPIC, topics);
            count(workspaceId, workspaceName, OTHER, other);
        }

        /**
         * Reads the prototype rows of the projects this tick has not read yet, in one query and one read-only
         * transaction, and asks the algorithm for each project's labeller. The tick stops waiting at {@code until}. A
         * failed read stops arrival for the tick and returns false.
         *
         * <p>The one place arrival reads MySQL: a cache can be put in front of it here, if a measurement shows the need.
         */
        private boolean read(String workspaceId, String workspaceName, Set<UUID> projectIds, Instant until) {
            var toRead = projectIds.stream().filter(projectId -> !labellers.containsKey(projectId)).toList();
            if (toRead.isEmpty()) {
                return true;
            }
            List<TopicPrototype> rows;
            try {
                rows = Mono.fromCallable(() -> template.inTransaction(READ_ONLY,
                        handle -> handle.attach(TopicDAO.class).findPrototypes(workspaceId, toRead)))
                        .subscribeOn(Schedulers.boundedElastic())
                        .timeout(left(until))
                        .block();
            } catch (RuntimeException exception) {
                TopicVectorsTickService.rethrowIfInterrupted(exception);
                stop(workspaceId, workspaceName, READ, exception);
                log.warn("Topic arrival read failed, arrival stops for this tick, workspace '{}', projects '{}'",
                        workspaceId, toRead.size(), exception);
                return false;
            }
            // In position order within each project: the query's order, which grouping keeps.
            var byProject = rows.stream().collect(Collectors.groupingBy(TopicPrototype::projectId));
            for (var projectId : toRead) {
                labellers.put(projectId, algorithm.labeller(byProject.getOrDefault(projectId, List.of())));
            }
            return true;
        }

        private void stop(String workspaceId, String workspaceName, String phase, Throwable error) {
            stopped = true;
            failures.add(1, TopicsWorkspaceNames.builder(workspaceId, workspaceName)
                    .put(stringKey("phase"), phase)
                    .put(ERROR_TYPE_KEY, ErrorMetricsResolver.errorType(error))
                    .build());
        }
    }

    private void count(String workspaceId, String workspaceName, String outcome, int traces) {
        if (traces > 0) {
            labelled.add(traces, TopicsWorkspaceNames.builder(workspaceId, workspaceName)
                    .put(stringKey("outcome"), outcome).build());
        }
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    /** The time left until {@code until}, zero once it has passed. */
    private static Duration left(Instant until) {
        var left = Duration.between(Instant.now(), until);
        return left.isNegative() ? Duration.ZERO : left;
    }

    /** The vectors' values, one vector after another, as the labeller takes them. */
    private static float[] flatten(List<TopicVector> vectors) {
        int dimensions = TopicsRebuildService.DIMENSIONS;
        var out = new float[vectors.size() * dimensions];
        for (int r = 0; r < vectors.size(); r++) {
            var values = vectors.get(r).vector();
            for (int d = 0; d < dimensions; d++) {
                out[r * dimensions + d] = values.get(d);
            }
        }
        return out;
    }
}
