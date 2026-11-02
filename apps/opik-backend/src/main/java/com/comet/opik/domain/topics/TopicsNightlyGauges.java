package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
import io.dropwizard.lifecycle.Managed;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * The nightly job's state gauges, read from {@code topic_nights} and {@code topic_night_projects}. The nightly alerts
 * read these, not the counters: a nightly counter's series is born on the night's pod, so {@code increase()} misses the
 * event that creates it.
 *
 * <p>Every pod reads MySQL at most once per {@link #REFRESH_INTERVAL}, on its own thread, and keeps what it read; a
 * scrape only reads the kept values, and ages are computed then from kept timestamps. Every pod publishes the same
 * values: aggregate them with {@code max}. Workspace names are resolved when MySQL is read, never in a callback. Nothing
 * is published while the nightly job is off, nor before the first night ever, except the refresher's own heartbeat
 * ({@code state_age_seconds}), which is published whenever the job is on. A read that fails keeps the last values and
 * counts in {@code state_refresh_failures}.
 *
 * <p>A gauge is one name, a description, and a list of series built at refresh: one more is a query and a
 * {@link #level} call.
 */
@Slf4j
@Singleton
@EagerSingleton
public class TopicsNightlyGauges implements Managed {

    static final Duration REFRESH_INTERVAL = Duration.ofMinutes(1);
    /** A project is stale after this many ended nights expected and without a saved run. */
    static final int STALE_NIGHTS = 3;
    /** A project is starved after this many ended nights not reached. */
    static final int STARVED_NIGHTS = 2;

    static final String STATE_AGE = "topics.nightly.state_age_seconds";
    static final String STATE_REFRESH_FAILURES = "topics.nightly.state_refresh_failures";
    static final String LAST_STARTED_AGE = "topics.nightly.last_started_age_seconds";
    static final String RUNNING = "topics.nightly.running_seconds";
    static final String LAST_NIGHT_PROJECTS = "topics.nightly.last_night.projects";
    static final String LAST_NIGHT_RUNS = "topics.nightly.last_night.runs";
    static final String STALE_PROJECTS = "topics.nightly.stale_projects";
    static final String STARVED_PROJECTS = "topics.nightly.starved_projects";
    static final String MISMATCHED = "topics.workspaces.mismatched";

    private static final AttributeKey<String> DECISION = stringKey("decision");
    private static final AttributeKey<String> REASON = stringKey("reason");
    private static final AttributeKey<String> STATUS = stringKey("status");

    /** One series of a level gauge: its attributes, with the workspace name resolved at refresh. */
    record Series(@NonNull Attributes attributes, long value) {
    }

    /** What one refresh read. {@code runningSince} is null when no night runs. */
    record Snapshot(@NonNull Instant lastStartedAt, Instant runningSince, @NonNull Map<String, List<Series>> levels) {
    }

    /** The rows one refresh reads, in one transaction; workspace names are resolved after it. */
    private record Read(Instant lastStartedAt, Instant runningSince, List<TopicNightDAO.ProjectCount> projects,
            List<TopicNightDAO.RunCount> runs, List<TopicNightDAO.WorkspaceCount> stale,
            List<TopicNightDAO.WorkspaceCount> starved, List<String> eligible, List<String> mismatched) {
    }

    private final TopicsConfig config;
    private final TransactionTemplate template;
    private final TopicsWorkspaceNames names;
    private final Clock clock;
    private final LongCounter refreshFailures;

    /** Null when off, before the first night, and before the first read. */
    private volatile Snapshot snapshot;
    /** When MySQL was last read; null before the first read that worked. */
    private volatile Instant lastRead;
    /** When the refresher started; null when off. */
    private volatile Instant began;
    private ScheduledExecutorService executor;

    @Inject
    public TopicsNightlyGauges(@NonNull @Config("topics") TopicsConfig config, @NonNull TransactionTemplate template,
            @NonNull TopicsWorkspaceNames names) {
        this(config, template, names, GlobalOpenTelemetry.get().getMeter("opik.topics"), Clock.systemUTC());
    }

    TopicsNightlyGauges(@NonNull TopicsConfig config, @NonNull TransactionTemplate template,
            @NonNull TopicsWorkspaceNames names, @NonNull Meter meter, @NonNull Clock clock) {
        this.config = config;
        this.template = template;
        this.names = names;
        this.clock = clock;
        this.refreshFailures = meter.counterBuilder(STATE_REFRESH_FAILURES)
                .setDescription("Reads of the night tables for the state gauges that failed; the gauges keep the "
                        + "last values read")
                .build();
        meter.gaugeBuilder(STATE_AGE).setUnit("s")
                .setDescription("Seconds since this pod last read the night tables for the state gauges, or since it "
                        + "started trying; published whenever the nightly job is on. Past a few minutes, every other "
                        + "state gauge is stale")
                .buildWithCallback(measurement -> Optional.ofNullable(lastRead).or(() -> Optional.ofNullable(began))
                        .filter(__ -> enabled())
                        .ifPresent(from -> measurement.record(secondsSince(from))));
        meter.gaugeBuilder(LAST_STARTED_AGE).setUnit("s")
                .setDescription("Seconds since the latest night started, scheduled or by hand")
                .buildWithCallback(measurement -> current()
                        .ifPresent(state -> measurement.record(secondsSince(state.lastStartedAt()))));
        meter.gaugeBuilder(RUNNING).setUnit("s")
                .setDescription("Seconds the oldest night still running has run; 0 when none runs. A night whose pod "
                        + "died stays running until the next night's housekeeping fails it")
                .buildWithCallback(measurement -> current().ifPresent(state -> measurement.record(
                        state.runningSince() == null ? 0 : secondsSince(state.runningSince()))));
        level(meter, LAST_NIGHT_PROJECTS, "Projects of the latest ended night, by workspace, decision and skip reason; "
                + "count_failed is one per workspace whose count read failed");
        level(meter, LAST_NIGHT_RUNS, "Runs the latest ended night started, by workspace and status (saved, failed, "
                + "running when marking it failed threw); a run that could not start is failed");
        level(meter, STALE_PROJECTS, "Projects expected on each of the last 3 ended nights (any decision but a skip "
                + "as small, deleted or already_saved) without a saved run since, per eligible workspace; 0 with none");
        level(meter, STARVED_PROJECTS, "Projects not_reached on each of the last 2 ended nights, per eligible "
                + "workspace; 0 with none");
        level(meter, MISMATCHED, "1 for a workspace enabled in topic_workspaces and not in ai_label_workspaces: no "
                + "night runs it");
    }

    @Override
    public void start() {
        if (!enabled()) {
            return;
        }
        begin();
        executor = Executors.newSingleThreadScheduledExecutor(
                Thread.ofPlatform().name("topics-nightly-gauges").daemon(true).factory());
        executor.scheduleWithFixedDelay(this::refresh, 0, REFRESH_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    /** Starts the heartbeat's clock and sets the failure counter's series, before the first read. */
    void begin() {
        began = clock.instant();
        refreshFailures.add(0);
    }

    /** Reads the night tables once and keeps the result. Off, it reads nothing. */
    void refresh() {
        if (!enabled()) {
            return;
        }
        Read read;
        try {
            read = template.inTransaction(READ_ONLY, handle -> read(handle.attach(TopicNightDAO.class),
                    handle.attach(TopicWorkspaceDAO.class)));
        } catch (RuntimeException exception) {
            refreshFailures.add(1);
            log.warn("Topics nightly gauges could not read the night tables; they keep the last values", exception);
            return;
        }
        lastRead = clock.instant();
        snapshot = read == null ? null : snapshot(read);
    }

    /** The values a scrape publishes now; empty when off or before the first night. */
    Optional<Snapshot> current() {
        return enabled() ? Optional.ofNullable(snapshot) : Optional.empty();
    }

    private boolean enabled() {
        return config.isEnabled() && config.getNightly().isEnabled();
    }

    /** Null before the first night ever. */
    private Read read(TopicNightDAO nights, TopicWorkspaceDAO workspaces) {
        var lastStartedAt = nights.lastStartedAt();
        if (lastStartedAt == null) {
            return null;
        }
        var ended = nights.findLatestEnded(Math.max(STALE_NIGHTS, STARVED_NIGHTS));
        var latest = ended.isEmpty() ? null : ended.getFirst().night();
        return new Read(lastStartedAt, nights.runningSince(),
                latest == null ? List.of() : nights.countProjects(latest),
                latest == null ? List.of() : nights.countRuns(latest),
                ended.size() < STALE_NIGHTS
                        ? List.of()
                        : nights.countStale(dates(ended, STALE_NIGHTS), STALE_NIGHTS,
                                ended.get(STALE_NIGHTS - 1).startedAt()),
                ended.size() < STARVED_NIGHTS
                        ? List.of()
                        : nights.countStarved(dates(ended, STARVED_NIGHTS), STARVED_NIGHTS),
                workspaces.eligibleWorkspaceIds(),
                workspaces.mismatchedWorkspaceIds());
    }

    private static List<LocalDate> dates(List<TopicNight> nights, int count) {
        return nights.subList(0, count).stream().map(TopicNight::night).toList();
    }

    private Snapshot snapshot(Read read) {
        var levels = new HashMap<String, List<Series>>();
        for (var row : read.projects()) {
            var attributes = workspace(row.workspaceId()).put(DECISION, row.decision());
            if (row.reason() != null) {
                attributes.put(REASON, row.reason());
            }
            add(levels, LAST_NIGHT_PROJECTS, attributes, row.projects());
        }
        for (var row : read.runs()) {
            add(levels, LAST_NIGHT_RUNS, workspace(row.workspaceId()).put(STATUS, row.runStatus()), row.runs());
        }
        perWorkspace(levels, STALE_PROJECTS, read.eligible(), read.stale());
        perWorkspace(levels, STARVED_PROJECTS, read.eligible(), read.starved());
        read.mismatched().forEach(workspaceId -> add(levels, MISMATCHED, workspace(workspaceId), 1));
        return new Snapshot(read.lastStartedAt(), read.runningSince(), Map.copyOf(levels));
    }

    /** Every eligible workspace, 0 unless counted: the series exists before its first problem. */
    private void perWorkspace(Map<String, List<Series>> levels, String name, List<String> eligible,
            List<TopicNightDAO.WorkspaceCount> counts) {
        var byWorkspace = counts.stream().collect(Collectors.toMap(TopicNightDAO.WorkspaceCount::workspaceId,
                TopicNightDAO.WorkspaceCount::projects));
        eligible.forEach(workspaceId -> add(levels, name, workspace(workspaceId),
                byWorkspace.getOrDefault(workspaceId, 0L)));
    }

    private AttributesBuilder workspace(String workspaceId) {
        return TopicsWorkspaceNames.builder(workspaceId, names.name(workspaceId));
    }

    private static void add(Map<String, List<Series>> levels, String name, AttributesBuilder attributes, long value) {
        levels.computeIfAbsent(name, key -> new ArrayList<>()).add(new Series(attributes.build(), value));
    }

    private void level(Meter meter, String name, String description) {
        meter.gaugeBuilder(name).ofLongs().setDescription(description)
                .buildWithCallback(measurement -> current().ifPresent(state -> state.levels()
                        .getOrDefault(name, List.of())
                        .forEach(series -> measurement.record(series.value(), series.attributes()))));
    }

    private double secondsSince(Instant from) {
        return Math.max(0, Duration.between(from, clock.instant()).toMillis() / 1e3);
    }
}
