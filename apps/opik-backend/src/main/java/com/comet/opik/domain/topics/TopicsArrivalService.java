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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.ERROR_TYPE_KEY;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * Topic on arrival: right after the vectors tick writes a chunk's vectors, each trace gets a topic by the project's
 * saved list, as a {@code trace_topics} row with source {@code arrival} and an empty run id. The next night writes a
 * row for every trace of its window, and the latest row wins, so an arrival label holds until then.
 *
 * <p>The rule is the {@link TopicAlgorithm}'s ({@link TopicAlgorithm#arrival}); this service reads the saved list once
 * per project per tick, bounds the time, writes the rows and counts. With {@link KMeansTopicAlgorithm}, a trace gets
 * its nearest live prototype's topic, as the night assigns. A project whose list labels nothing yet gets no row; its
 * traces wait for its first build.
 *
 * <p>Best effort: a failed MySQL read or {@code trace_topics} insert is logged and counted, never thrown, and changes
 * nothing in the tick. The night labels those traces.
 *
 * <p>Bounded in time: one step ends within {@code topics.arrival.timeout} and within the tick's deadline, whichever
 * comes first, so arrival never holds the tick past its budget. A step with less than a second of that left does not
 * start; its traces count as {@value #OVER_BUDGET}. The read and the insert run under that time, and a read or insert
 * that outlasts it counts as a failure with error type {@code TimeoutException}.
 */
@Slf4j
@Singleton
public class TopicsArrivalService {

    static final String TOPIC = "topic";
    static final String OTHER = "other";
    static final String NO_PROTOTYPES = "no_prototypes";
    static final String DIMENSION_MISMATCH = "dimension_mismatch";
    static final String OVER_BUDGET = "over_budget";

    static final String READ = "read";
    static final String ASSIGN = "assign";
    static final String INSERT = "insert";

    // MySQL's query timeout is in whole seconds: a step starts only with at least one left.
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
                        + "dimension_mismatch (its list and the vectors differ in length), over_budget (less "
                        + "than a second left of the step's time or of the tick)")
                .build();
        this.failures = meter.counterBuilder("topics.arrival.failures")
                .setDescription("Arrival steps that failed, by phase: read (the projects' topics in MySQL), assign, "
                        + "insert (trace_topics); with error type and workspace, TimeoutException when the step ran "
                        + "out of time. The night labels those traces")
                .build();
        this.duration = meter.histogramBuilder("topics.arrival.duration_seconds").setUnit("s")
                .setDescription("Wall time of the arrival step after one vectors insert, by workspace")
                .build();
    }

    /** One tick's arrival step, which reads each project's topic list once; a no-op when arrival is off. */
    public Tick tick() {
        return new Tick(config.getArrival().isEnabled());
    }

    /** The arrival step of one tick. Not thread safe: the tick is one thread. */
    public final class Tick {

        private final boolean enabled;
        // Per project read this tick, its arrival rule, or empty when its list labels nothing yet. A project whose read
        // or rule failed is in neither map: it is not read again this tick.
        private final Map<UUID, Optional<TopicAlgorithm.Arrival>> projects = new HashMap<>();
        private final Set<UUID> unusable = new HashSet<>();
        private final Set<UUID> mismatchLogged = new HashSet<>();

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
                log.warn("Topic arrival failed, workspace '{}', vectors '{}'; the night labels them", workspaceId,
                        vectors.size(), exception);
            } finally {
                duration.record((System.nanoTime() - started) / 1e9,
                        TopicsWorkspaceNames.builder(workspaceId, workspaceName).build());
            }
        }

        private void labelChunk(String workspaceId, String workspaceName, List<TopicVector> vectors, Instant until) {
            var byProject = vectors.stream().collect(Collectors.groupingBy(TopicVector::projectId,
                    LinkedHashMap::new, Collectors.toList()));
            read(workspaceId, workspaceName, byProject.keySet(), until);
            var rows = new ArrayList<TraceTopic>(vectors.size());
            int topics = 0;
            int other = 0;
            for (var entry : byProject.entrySet()) {
                var projectId = entry.getKey();
                if (unusable.contains(projectId)) {
                    continue;
                }
                var group = entry.getValue();
                var arrival = projects.get(projectId).orElse(null);
                if (arrival == null) {
                    count(workspaceId, workspaceName, NO_PROTOTYPES, group.size());
                    continue;
                }
                var assignable = group.stream()
                        .filter(vector -> vector.vector().size() == arrival.dimensions())
                        .toList();
                if (assignable.size() < group.size()) {
                    count(workspaceId, workspaceName, DIMENSION_MISMATCH, group.size() - assignable.size());
                    if (mismatchLogged.add(projectId)) {
                        log.warn("Topic arrival skipped vectors of another length than the prototypes, workspace '{}', "
                                + "project '{}', prototypes '{}', vectors '{}'", workspaceId, projectId,
                                arrival.dimensions(), group.size() - assignable.size());
                    }
                }
                if (assignable.isEmpty()) {
                    continue;
                }
                var topicIds = arrival.label(flatten(assignable, arrival.dimensions()));
                Preconditions.checkState(topicIds.length == assignable.size(),
                        "the arrival rule labelled '%s' of '%s' vectors", topicIds.length, assignable.size());
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
            try {
                traceTopicsDAO.insert(rows).timeout(left(until)).block();
            } catch (RuntimeException exception) {
                fail(workspaceId, workspaceName, INSERT, exception);
                log.warn("Topic arrival insert failed, workspace '{}', rows '{}'; the night labels them", workspaceId,
                        rows.size(), exception);
                return;
            }
            count(workspaceId, workspaceName, TOPIC, topics);
            count(workspaceId, workspaceName, OTHER, other);
        }

        /**
         * Reads the topic lists of the projects this tick has not read yet, in one transaction, until {@code until}.
         * The tick stops waiting then; each query's own timeout ends the read soon after.
         */
        private void read(String workspaceId, String workspaceName, Set<UUID> projectIds, Instant until) {
            var toRead = projectIds.stream()
                    .filter(projectId -> !projects.containsKey(projectId) && !unusable.contains(projectId))
                    .toList();
            if (toRead.isEmpty()) {
                return;
            }
            var left = left(until);
            int queryTimeout = (int) Math.max(1, left.toSeconds());
            Map<UUID, Saved> saved;
            try {
                saved = Mono.fromCallable(() -> template.inTransaction(READ_ONLY, handle -> {
                    handle.getConfig(SqlStatements.class).setQueryTimeout(queryTimeout);
                    var dao = handle.attach(TopicDAO.class);
                    var read = new HashMap<UUID, Saved>();
                    for (var projectId : dao.findProjectsWithLiveTopics(toRead)) {
                        read.put(projectId, new Saved(dao.findLive(projectId), dao.findPrototypes(projectId)));
                    }
                    return read;
                })).subscribeOn(Schedulers.boundedElastic()).timeout(left).block();
            } catch (RuntimeException exception) {
                unusable.addAll(toRead);
                fail(workspaceId, workspaceName, READ, exception);
                log.warn("Topic arrival read failed, workspace '{}', projects '{}'; not labelling them this tick",
                        workspaceId, toRead.size(), exception);
                return;
            }
            for (var projectId : toRead) {
                var project = saved.get(projectId);
                if (project == null) {
                    projects.put(projectId, Optional.empty());
                    continue;
                }
                try {
                    projects.put(projectId, algorithm.arrival(project.live(), project.rows()));
                } catch (RuntimeException exception) {
                    unusable.add(projectId);
                    fail(workspaceId, workspaceName, ASSIGN, exception);
                    log.warn("Topic arrival can't build its rule from the saved list, workspace '{}', project '{}'; "
                            + "not labelling it this tick", workspaceId, projectId, exception);
                }
            }
        }
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
