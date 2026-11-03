package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.metrics.ErrorMetricsResolver;
import com.google.common.base.Preconditions;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.statement.SqlStatements;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.ERROR_TYPE_KEY;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * Topic on arrival: right after the vectors tick writes a chunk's vectors, each trace gets a topic by the project's
 * saved list, as a {@code trace_topics} row with source {@code arrival} and an empty run id. A later night whose window
 * covers the trace writes its own row, and the latest row wins, so an arrival label holds until then.
 *
 * <p>The rule is the {@link TopicAlgorithm}'s ({@link TopicAlgorithm#arrival}); this service reads the saved list once
 * per project per tick, bounds the time, writes the rows and counts. With {@link KMeansTopicAlgorithm}, a trace gets
 * its nearest live prototype's topic, as the night assigns. A project whose list labels nothing yet gets no row; its
 * traces wait for its first build. A trace whose summary the night would skip (only whitespace) gets no row either.
 *
 * <p>Best effort: a failure is logged and counted, never thrown, and the tick writes and counts its vectors as without
 * arrival. The night labels those traces. After a failed read or insert, arrival stops for the rest of the tick, so a
 * failing MySQL or ClickHouse costs the tick at most one step's time; a project whose rule fails is skipped for the rest
 * of the tick. The traces it then skips count as {@value #AFTER_FAILURE}.
 *
 * <p>Bounded in time: one step ends within {@code topics.arrival.timeout} and within the tick's deadline, whichever
 * comes first, so arrival never holds the tick past its budget. A step with less than a second of that left does not
 * start, or does not insert; its traces count as {@value #OVER_BUDGET}. A read or insert that outlasts it counts as a
 * failure with error type {@code TimeoutException}.
 */
@Slf4j
@Singleton
public class TopicsArrivalService {

    static final String TOPIC = "topic";
    static final String OTHER = "other";
    static final String NO_PROTOTYPES = "no_prototypes";
    static final String DIMENSION_MISMATCH = "dimension_mismatch";
    static final String NO_TEXT = "no_text";
    static final String OVER_BUDGET = "over_budget";
    static final String AFTER_FAILURE = "after_failure";

    static final String READ = "read";
    static final String ASSIGN = "assign";
    static final String INSERT = "insert";

    // MySQL's query timeout is in whole seconds: a step starts, and inserts, only with at least one left.
    private static final Duration MIN_TIME = Duration.ofSeconds(1);

    private final TopicsConfig config;
    private final TransactionTemplate template;
    private final TraceTopicsDAO traceTopicsDAO;
    private final TopicAlgorithm algorithm;

    private final LongCounter labelled;
    private final LongCounter failures;
    private final DoubleHistogram duration;

    /** A project's topic list as one tick read it. */
    private record Saved(List<Topic> live, List<TopicPrototype> rows) {
    }

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
                        + "(a trace_topics row written), no_prototypes (the project's list labels nothing yet), "
                        + "dimension_mismatch (its list and the vectors differ in length), no_text (a summary the "
                        + "night skips), over_budget (less than a second left of the step's time or of the tick), "
                        + "after_failure (arrival stopped for the tick, or the project's rule failed, earlier in it)")
                .build();
        this.failures = meter.counterBuilder("topics.arrival.failures")
                .setDescription("Arrival steps that failed, by phase: read (the projects' topics in MySQL), assign "
                        + "(the algorithm's rule), insert (trace_topics); with error type and workspace, "
                        + "TimeoutException when the step ran out of time. The night labels those traces")
                .build();
        this.duration = meter.histogramBuilder("topics.arrival.duration_seconds").setUnit("s")
                .setDescription("Wall time of the arrival step after one vectors insert, by workspace; steps that "
                        + "do not start are not recorded")
                .build();
    }

    /** One tick's arrival step, which reads each project's topic list once; a no-op when arrival is off. */
    public Tick tick() {
        return new Tick(config.getArrival().isEnabled());
    }

    /** The arrival step of one tick. Not thread safe: the tick is one thread. */
    public final class Tick {

        private final boolean enabled;
        // Per project read this tick: its arrival rule, or empty when its list labels nothing yet. A project whose
        // read or rule failed is in unusable instead, and is not read again this tick.
        private final Map<UUID, Optional<TopicAlgorithm.Arrival>> projects = new HashMap<>();
        private final Set<UUID> unusable = new HashSet<>();
        private final Set<UUID> mismatchLogged = new HashSet<>();
        // Set by a failed read or insert: arrival does nothing more this tick.
        private boolean stopped;

        private Tick(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * Labels the vectors one insert wrote, all of one workspace, and writes their {@code trace_topics} rows, within
         * {@code topics.arrival.timeout} and before {@code tickDeadline}. Never throws.
         *
         * @param workspaceName the name the tick resolved, for the metrics: this step looks up nothing
         * @param tickDeadline the end of the tick's budget
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
            var until = min(now.plus(config.getArrival().getTimeout().toJavaDuration()), tickDeadline);
            if (Duration.between(now, until).compareTo(MIN_TIME) < 0) {
                count(workspaceId, workspaceName, OVER_BUDGET, vectors.size());
                return;
            }
            long started = System.nanoTime();
            try {
                labelChunk(workspaceId, workspaceName, vectors, until);
            } catch (RuntimeException exception) {
                fail(workspaceId, workspaceName, ASSIGN, exception);
                log.warn("Topic arrival failed, the night labels them, workspace '{}', vectors '{}'", workspaceId,
                        vectors.size(), exception);
            } finally {
                duration.record((System.nanoTime() - started) / 1e9,
                        TopicsWorkspaceNames.builder(workspaceId, workspaceName).build());
            }
        }

        private void labelChunk(String workspaceId, String workspaceName, List<TopicVector> vectors, Instant until) {
            var byProject = vectors.stream().collect(Collectors.groupingBy(TopicVector::projectId,
                    LinkedHashMap::new, Collectors.toList()));
            // A project that fails in this chunk is counted by its failure; one that failed before, by after_failure.
            var failedBefore = Set.copyOf(unusable);
            if (!read(workspaceId, workspaceName, byProject.keySet(), until)) {
                return;
            }
            var rows = new ArrayList<TraceTopic>(vectors.size());
            int topics = 0;
            int other = 0;
            for (var entry : byProject.entrySet()) {
                var projectId = entry.getKey();
                var group = entry.getValue();
                if (unusable.contains(projectId)) {
                    if (failedBefore.contains(projectId)) {
                        count(workspaceId, workspaceName, AFTER_FAILURE, group.size());
                    }
                    continue;
                }
                var arrival = projects.get(projectId).orElse(null);
                if (arrival == null) {
                    count(workspaceId, workspaceName, NO_PROTOTYPES, group.size());
                    continue;
                }
                var withText = group.stream()
                        .filter(vector -> !TopicText.pythonStrip(vector.text()).isEmpty())
                        .toList();
                count(workspaceId, workspaceName, NO_TEXT, group.size() - withText.size());
                var assignable = withText.stream()
                        .filter(vector -> vector.vector().size() == arrival.dimensions())
                        .toList();
                if (assignable.size() < withText.size()) {
                    count(workspaceId, workspaceName, DIMENSION_MISMATCH, withText.size() - assignable.size());
                    if (mismatchLogged.add(projectId)) {
                        log.warn("Topic arrival skipped vectors of another length than the prototypes, workspace '{}', "
                                + "project '{}', prototypes '{}', vectors '{}'", workspaceId, projectId,
                                arrival.dimensions(), withText.size() - assignable.size());
                    }
                }
                if (assignable.isEmpty()) {
                    continue;
                }
                UUID[] topicIds;
                try {
                    topicIds = arrival.label(flatten(assignable, arrival.dimensions()));
                    Preconditions.checkState(topicIds.length == assignable.size(),
                            "the arrival rule labelled '%s' of '%s' vectors", topicIds.length, assignable.size());
                } catch (RuntimeException exception) {
                    unusable.add(projectId);
                    fail(workspaceId, workspaceName, ASSIGN, exception);
                    log.warn("Topic arrival rule failed, not labelling the project this tick, workspace '{}', "
                            + "project '{}'", workspaceId, projectId, exception);
                    continue;
                }
                for (int i = 0; i < assignable.size(); i++) {
                    var vector = assignable.get(i);
                    rows.add(TraceTopic.builder()
                            .workspaceId(vector.workspaceId())
                            .projectId(projectId)
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
            var left = left(until);
            if (left.compareTo(MIN_TIME) < 0) {
                count(workspaceId, workspaceName, OVER_BUDGET, rows.size());
                return;
            }
            try {
                traceTopicsDAO.insert(rows).timeout(left).block();
            } catch (RuntimeException exception) {
                stopped = true;
                fail(workspaceId, workspaceName, INSERT, exception);
                log.warn("Topic arrival insert failed, arrival stops for this tick, workspace '{}', rows '{}'",
                        workspaceId, rows.size(), exception);
                return;
            }
            count(workspaceId, workspaceName, TOPIC, topics);
            count(workspaceId, workspaceName, OTHER, other);
        }

        /**
         * Reads the topic lists of the projects this tick has not read yet, in one transaction, until {@code until},
         * and builds their rules. The tick stops waiting then. The read itself stops soon after: each query's timeout
         * is the time left, rounded up to a second, and no query starts after {@code until}. A failed read stops
         * arrival for the tick and returns false; a project whose rule fails is unusable for the tick.
         */
        private boolean read(String workspaceId, String workspaceName, Set<UUID> projectIds, Instant until) {
            var toRead = projectIds.stream()
                    .filter(projectId -> !projects.containsKey(projectId) && !unusable.contains(projectId))
                    .toList();
            if (toRead.isEmpty()) {
                return true;
            }
            Map<UUID, Saved> saved;
            try {
                saved = Mono.fromCallable(() -> template.inTransaction(READ_ONLY, handle -> {
                    var read = new HashMap<UUID, Saved>();
                    for (var projectId : dao(handle, until).findProjectsWithLiveTopics(toRead)) {
                        var live = dao(handle, until).findLive(projectId);
                        read.put(projectId, new Saved(live, dao(handle, until).findPrototypes(projectId)));
                    }
                    return read;
                })).subscribeOn(Schedulers.boundedElastic()).timeout(left(until)).block();
            } catch (RuntimeException exception) {
                stopped = true;
                fail(workspaceId, workspaceName, READ, exception);
                log.warn("Topic arrival read failed, arrival stops for this tick, workspace '{}', projects '{}'",
                        workspaceId, toRead.size(), exception);
                return false;
            }
            for (var projectId : toRead) {
                var project = saved.get(projectId);
                if (project == null) {
                    projects.put(projectId, Optional.empty());
                    continue;
                }
                try {
                    projects.put(projectId, Objects.requireNonNull(algorithm.arrival(project.live(), project.rows()),
                            "the topic algorithm returned a null arrival rule"));
                } catch (RuntimeException exception) {
                    unusable.add(projectId);
                    fail(workspaceId, workspaceName, ASSIGN, exception);
                    log.warn("Topic arrival rule failed, not labelling the project this tick, workspace '{}', "
                            + "project '{}'", workspaceId, projectId, exception);
                }
            }
            return true;
        }
    }

    /**
     * The topics DAO for one more query of the read: its timeout is the time left until {@code until}, rounded up so
     * that the tick's own timeout, not MySQL's, ends a slow step. Throws once {@code until} has passed.
     */
    private static TopicDAO dao(Handle handle, Instant until) {
        long millis = Duration.between(Instant.now(), until).toMillis();
        if (millis <= 0) {
            throw new IllegalStateException(new TimeoutException("the arrival step's time ran out during its read"));
        }
        handle.getConfig(SqlStatements.class).setQueryTimeout((int) ((millis + 999) / 1000));
        return handle.attach(TopicDAO.class);
    }

    private void count(String workspaceId, String workspaceName, String outcome, int traces) {
        if (traces > 0) {
            labelled.add(traces, TopicsWorkspaceNames.builder(workspaceId, workspaceName)
                    .put(stringKey("outcome"), outcome).build());
        }
    }

    private void fail(String workspaceId, String workspaceName, String phase, Throwable error) {
        failures.add(1, TopicsWorkspaceNames.builder(workspaceId, workspaceName)
                .put(stringKey("phase"), phase)
                .put(ERROR_TYPE_KEY, ErrorMetricsResolver.errorType(error))
                .build());
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    /** The time left until {@code until}, zero once it has passed. */
    private static Duration left(Instant until) {
        var left = Duration.between(Instant.now(), until);
        return left.isNegative() ? Duration.ZERO : left;
    }

    private static float[] flatten(List<TopicVector> vectors, int dimensions) {
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
