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
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

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
import java.util.stream.Stream;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.ERROR_TYPE_KEY;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * Topic on arrival: right after the vectors tick writes a chunk's vectors, each trace gets the topic of its nearest
 * prototype, as a {@code trace_topics} row with source {@code arrival} and an empty run id. The next night writes a row
 * for every trace of its window, and the latest row wins, so an arrival label holds until then.
 *
 * <p>The prototypes are the project's live topics' and the known-Other ones, assigned by {@link PrototypeAssigner} with
 * the night's Other gate and threshold. The prototypes a nightly update keeps for retired topics it may revive are left
 * out: only a night brings such a topic back. A project without a live topic prototype gets no row; its traces wait for
 * its first build.
 *
 * <p>Best effort: a failed MySQL read or {@code trace_topics} insert is logged and counted, never thrown, and changes
 * nothing in the tick. The night labels those traces.
 */
@Slf4j
@Singleton
public class TopicsArrivalService {

    static final String TOPIC = "topic";
    static final String OTHER = "other";
    static final String NO_PROTOTYPES = "no_prototypes";
    static final String DIMENSION_MISMATCH = "dimension_mismatch";

    static final String READ = "read";
    static final String ASSIGN = "assign";
    static final String INSERT = "insert";

    private final TopicsConfig config;
    private final TransactionTemplate template;
    private final TraceTopicsDAO traceTopicsDAO;

    private final LongCounter labelled;
    private final LongCounter failures;
    private final DoubleHistogram duration;

    /** A project's assignment, with the live topic id of each owner index the assigner returns. */
    @Builder
    record Prototypes(@NonNull PrototypeAssigner assigner, @NonNull List<UUID> topicIds, int dimensions) {

        UUID topicId(int owner) {
            return owner == PrototypeAssigner.OTHER ? TraceTopic.OTHER : topicIds.get(owner);
        }
    }

    /** A project's topic list as one tick read it. */
    private record Saved(List<Topic> live, List<TopicPrototype> rows) {
    }

    @Inject
    public TopicsArrivalService(@NonNull @Config("topics") TopicsConfig config, @NonNull TransactionTemplate template,
            @NonNull TraceTopicsDAO traceTopicsDAO) {
        this(config, template, traceTopicsDAO, GlobalOpenTelemetry.get().getMeter("opik.topics"));
    }

    TopicsArrivalService(@NonNull TopicsConfig config, @NonNull TransactionTemplate template,
            @NonNull TraceTopicsDAO traceTopicsDAO, @NonNull Meter meter) {
        this.config = config;
        this.template = template;
        this.traceTopicsDAO = traceTopicsDAO;
        this.labelled = meter.counterBuilder("topics.arrival.labelled")
                .setDescription("Traces the vectors tick labelled on arrival, by outcome and workspace: topic or other "
                        + "(a trace_topics row written), no_prototypes (the project has no live topic yet), "
                        + "dimension_mismatch (its prototypes and the vectors differ in length)")
                .build();
        this.failures = meter.counterBuilder("topics.arrival.failures")
                .setDescription("Arrival steps that failed, by phase: read (the projects' topics in MySQL), assign, "
                        + "insert (trace_topics); with error type and workspace. The night labels those traces")
                .build();
        this.duration = meter.histogramBuilder("topics.arrival.duration_seconds").setUnit("s")
                .setDescription("Wall time of the arrival step after one vectors insert, by workspace")
                .build();
    }

    /** One tick's arrival step, which reads each project's topic list once; a no-op when arrival is off. */
    public Tick tick() {
        return new Tick(config.getArrival().isEnabled());
    }

    /**
     * The assignment by {@code live}'s prototypes and the known-Other ones, each in {@code rows}' order: the order the
     * night saved them in and assigned with, so a tie goes the same way. Empty without a prototype of a live topic.
     *
     * @param rows the project's prototypes by position; those of a topic not in {@code live} are left out
     */
    static Optional<Prototypes> prototypes(@NonNull List<Topic> live, @NonNull List<TopicPrototype> rows) {
        var owners = new HashMap<UUID, Integer>();
        for (var topic : live) {
            owners.put(topic.id(), owners.size());
        }
        var topicPrototypes = new ArrayList<float[]>();
        var topicOwners = new ArrayList<Integer>();
        var otherPrototypes = new ArrayList<float[]>();
        for (var row : rows) {
            if (row.topicId() == null) {
                otherPrototypes.add(row.values());
            } else if (owners.containsKey(row.topicId())) {
                topicPrototypes.add(row.values());
                topicOwners.add(owners.get(row.topicId()));
            }
        }
        if (topicPrototypes.isEmpty()) {
            return Optional.empty();
        }
        int dimensions = topicPrototypes.getFirst().length;
        Preconditions.checkState(Stream.concat(topicPrototypes.stream(), otherPrototypes.stream())
                .allMatch(values -> values.length == dimensions), "prototypes of more than one length");
        return Optional.of(Prototypes.builder()
                .assigner(new PrototypeAssigner(topicPrototypes, topicOwners, otherPrototypes, dimensions))
                .topicIds(live.stream().map(Topic::id).toList())
                .dimensions(dimensions)
                .build());
    }

    /** The arrival step of one tick. Not thread safe: the tick is one thread. */
    public final class Tick {

        private final boolean enabled;
        // Per project read this tick, its assignment, or empty without a live topic. A project whose read or assignment
        // failed is in neither map: it is not read again this tick.
        private final Map<UUID, Optional<Prototypes>> projects = new HashMap<>();
        private final Set<UUID> unusable = new HashSet<>();
        private final Set<UUID> mismatchLogged = new HashSet<>();

        private Tick(boolean enabled) {
            this.enabled = enabled;
        }

        /**
         * Labels the vectors one insert wrote, all of one workspace, and writes their {@code trace_topics} rows. Never
         * throws.
         *
         * @param workspaceName the name the tick resolved, for the metrics: this step looks up nothing
         */
        public void label(@NonNull String workspaceId, @NonNull String workspaceName,
                @NonNull List<TopicVector> vectors) {
            if (!enabled || vectors.isEmpty()) {
                return;
            }
            long started = System.nanoTime();
            try {
                labelChunk(workspaceId, workspaceName, vectors);
            } catch (RuntimeException exception) {
                fail(workspaceId, workspaceName, ASSIGN, exception);
                log.warn("Topic arrival failed, workspace '{}', vectors '{}'; the night labels them", workspaceId,
                        vectors.size(), exception);
            } finally {
                duration.record((System.nanoTime() - started) / 1e9,
                        TopicsWorkspaceNames.builder(workspaceId, workspaceName).build());
            }
        }

        private void labelChunk(String workspaceId, String workspaceName, List<TopicVector> vectors) {
            var byProject = vectors.stream().collect(Collectors.groupingBy(TopicVector::projectId,
                    LinkedHashMap::new, Collectors.toList()));
            read(workspaceId, workspaceName, byProject.keySet());
            var rows = new ArrayList<TraceTopic>(vectors.size());
            int topics = 0;
            int other = 0;
            for (var entry : byProject.entrySet()) {
                var projectId = entry.getKey();
                if (unusable.contains(projectId)) {
                    continue;
                }
                var group = entry.getValue();
                var prototypes = projects.get(projectId).orElse(null);
                if (prototypes == null) {
                    count(workspaceId, workspaceName, NO_PROTOTYPES, group.size());
                    continue;
                }
                var assignable = group.stream()
                        .filter(vector -> vector.vector().size() == prototypes.dimensions())
                        .toList();
                if (assignable.size() < group.size()) {
                    count(workspaceId, workspaceName, DIMENSION_MISMATCH, group.size() - assignable.size());
                    if (mismatchLogged.add(projectId)) {
                        log.warn("Topic arrival skipped vectors of another length than the prototypes, workspace '{}', "
                                + "project '{}', prototypes '{}', vectors '{}'", workspaceId, projectId,
                                prototypes.dimensions(), group.size() - assignable.size());
                    }
                }
                if (assignable.isEmpty()) {
                    continue;
                }
                var owners = prototypes.assigner().assign(flatten(assignable, prototypes.dimensions()));
                for (int i = 0; i < assignable.size(); i++) {
                    var vector = assignable.get(i);
                    rows.add(TraceTopic.builder()
                            .workspaceId(vector.workspaceId())
                            .projectId(projectId)
                            .traceId(vector.traceId())
                            .traceCreatedAt(vector.traceCreatedAt())
                            .topicId(prototypes.topicId(owners[i]))
                            .source(TraceTopic.Source.ARRIVAL)
                            .runId("")
                            .build());
                    if (owners[i] == PrototypeAssigner.OTHER) {
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
                traceTopicsDAO.insert(rows).block();
            } catch (RuntimeException exception) {
                fail(workspaceId, workspaceName, INSERT, exception);
                log.warn("Topic arrival insert failed, workspace '{}', rows '{}'; the night labels them", workspaceId,
                        rows.size(), exception);
                return;
            }
            count(workspaceId, workspaceName, TOPIC, topics);
            count(workspaceId, workspaceName, OTHER, other);
        }

        /** Reads the topic lists of the projects this tick has not read yet, in one transaction. */
        private void read(String workspaceId, String workspaceName, Set<UUID> projectIds) {
            var toRead = projectIds.stream()
                    .filter(projectId -> !projects.containsKey(projectId) && !unusable.contains(projectId))
                    .toList();
            if (toRead.isEmpty()) {
                return;
            }
            Map<UUID, Saved> saved;
            try {
                saved = template.inTransaction(READ_ONLY, handle -> {
                    var dao = handle.attach(TopicDAO.class);
                    var read = new HashMap<UUID, Saved>();
                    for (var projectId : dao.findProjectsWithLiveTopics(toRead)) {
                        read.put(projectId, new Saved(dao.findLive(projectId), dao.findPrototypes(projectId)));
                    }
                    return read;
                });
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
                    projects.put(projectId, prototypes(project.live(), project.rows()));
                } catch (RuntimeException exception) {
                    unusable.add(projectId);
                    fail(workspaceId, workspaceName, ASSIGN, exception);
                    log.warn("Topic arrival can't assign by the saved prototypes, workspace '{}', project '{}'; not "
                            + "labelling it this tick", workspaceId, projectId, exception);
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
