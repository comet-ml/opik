package com.comet.opik.domain.topics;

import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.infrastructure.TopicsConfig;
import io.dropwizard.lifecycle.Managed;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * The nightly job's state gauges, read from the night and attempt fields of {@code topic_projects}. The nightly alerts
 * read these for state, and the night counters for what a night did.
 *
 * <p>Every pod reads MySQL at most once per {@link #REFRESH_INTERVAL}, on its own thread, and keeps what it read; a
 * scrape only reads the kept values, and ages are computed then. Every pod publishes the same values: aggregate them
 * with {@code max}. Workspace names are resolved after MySQL is read, never in a callback. Nothing is published while
 * the nightly job is off; {@code state_age_seconds} is published whenever it is on. A read that fails keeps the last
 * values and counts in {@code state_refresh_failures}.
 */
@Slf4j
@Singleton
@EagerSingleton
public class TopicsNightlyGauges implements Managed {

    static final Duration REFRESH_INTERVAL = Duration.ofMinutes(1);
    /** A project is stale after this many failed attempts in a row. */
    static final int STALE_FAILURES = 3;
    /** A project is starved after this many nights in a row not reached. */
    static final int STARVED_NIGHTS = 2;

    static final String STATE_AGE = "topics.nightly.state_age_seconds";
    static final String STATE_REFRESH_FAILURES = "topics.nightly.state_refresh_failures";
    static final String LAST_NIGHT_AGE = "topics.nightly.last_night_age_seconds";
    static final String STALE_PROJECTS = "topics.nightly.stale_projects";
    static final String STARVED_PROJECTS = "topics.nightly.starved_projects";

    private static final AttributeKey<String> WORKSPACE_ID = stringKey("workspace_id");
    private static final AttributeKey<String> WORKSPACE_NAME = stringKey("workspace_name");

    /** One series of a per-workspace gauge, with the workspace name resolved at refresh. */
    @Builder(toBuilder = true)
    record Series(@NonNull Attributes attributes, long value) {
    }

    /** What one refresh read. {@code lastNight} is null before any night looked at a project. */
    @Builder(toBuilder = true)
    record Snapshot(LocalDate lastNight, @NonNull List<Series> stale, @NonNull List<Series> starved) {
    }

    /** The rows one refresh reads, in one transaction; names are resolved after it. */
    private record Read(LocalDate lastNight, List<TopicProjectNightDAO.WorkspaceCount> stale,
            List<TopicProjectNightDAO.WorkspaceCount> starved) {
    }

    private final TopicsConfig config;
    private final TransactionTemplate template;
    private final TopicWorkspaceService workspaceService;
    private final Function<String, String> names;
    private final Clock clock;
    private final LongCounter refreshFailures;

    /** Null when off and before the first read. */
    private volatile Snapshot snapshot;
    /** When MySQL was last read; null before the first read that worked. */
    private volatile Instant lastRead;
    /** When the refresher started; null when off. */
    private volatile Instant began;
    private ScheduledExecutorService executor;

    @Inject
    public TopicsNightlyGauges(@NonNull @Config("topics") TopicsConfig config, @NonNull TransactionTemplate template,
            @NonNull TopicWorkspaceService workspaceService, @NonNull WorkspaceNameService names) {
        this(config, template, workspaceService, names::nameOrId, GlobalOpenTelemetry.get().getMeter("opik.topics"),
                Clock.systemUTC());
    }

    TopicsNightlyGauges(@NonNull TopicsConfig config, @NonNull TransactionTemplate template,
            @NonNull TopicWorkspaceService workspaceService, @NonNull Function<String, String> names,
            @NonNull Meter meter, @NonNull Clock clock) {
        this.config = config;
        this.template = template;
        this.workspaceService = workspaceService;
        this.names = names;
        this.clock = clock;
        this.refreshFailures = meter.counterBuilder(STATE_REFRESH_FAILURES)
                .setDescription("Reads of topic_projects for the state gauges that failed; the gauges keep the last "
                        + "values read")
                .build();
        meter.gaugeBuilder(STATE_AGE).setUnit("s")
                .setDescription("Seconds since this pod last read topic_projects for the state gauges, or since it "
                        + "started trying; published whenever the nightly job is on. Past a few minutes, every other "
                        + "state gauge is stale")
                .buildWithCallback(measurement -> Optional.ofNullable(lastRead).or(() -> Optional.ofNullable(began))
                        .filter(__ -> enabled())
                        .ifPresent(from -> measurement.record(secondsSince(from))));
        meter.gaugeBuilder(LAST_NIGHT_AGE).setUnit("s")
                .setDescription("Seconds since the start of the latest night that looked at a project of an enabled "
                        + "workspace (its UTC date, not the job's start time); not published before the first")
                .buildWithCallback(measurement -> current().map(Snapshot::lastNight)
                        .ifPresent(night -> measurement.record(secondsSince(
                                night.atStartOfDay(ZoneOffset.UTC).toInstant()))));
        meter.gaugeBuilder(STALE_PROJECTS).ofLongs()
                .setDescription("Projects with a topic list whose last %s attempts or more failed in a row, per "
                        .formatted(STALE_FAILURES) + "enabled workspace; 0 with none")
                .buildWithCallback(measurement -> current().ifPresent(state -> state.stale()
                        .forEach(series -> measurement.record(series.value(), series.attributes()))));
        meter.gaugeBuilder(STARVED_PROJECTS).ofLongs()
                .setDescription("Projects the last %s nights or more in a row did not reach, per enabled "
                        .formatted(STARVED_NIGHTS) + "workspace; 0 with none")
                .buildWithCallback(measurement -> current().ifPresent(state -> state.starved()
                        .forEach(series -> measurement.record(series.value(), series.attributes()))));
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

    /** Reads {@code topic_projects} once and keeps the result. Off, it reads nothing. */
    void refresh() {
        if (!enabled()) {
            return;
        }
        List<String> enabled;
        Read read;
        try {
            enabled = workspaceService.enabledWorkspaceIds();
            read = enabled.isEmpty()
                    ? new Read(null, List.of(), List.of())
                    : template.inTransaction(READ_ONLY, handle -> {
                        var dao = handle.attach(TopicProjectNightDAO.class);
                        return new Read(dao.lastNight(enabled), dao.countStale(enabled, STALE_FAILURES),
                                dao.countStarved(enabled, STARVED_NIGHTS));
                    });
        } catch (RuntimeException exception) {
            refreshFailures.add(1);
            log.warn("Topics nightly gauges could not read topic_projects; they keep the last values", exception);
            return;
        }
        lastRead = clock.instant();
        snapshot = Snapshot.builder().lastNight(read.lastNight()).stale(perWorkspace(enabled, read.stale()))
                .starved(perWorkspace(enabled, read.starved())).build();
    }

    /** The values a scrape publishes now; empty when off or before the first read. */
    Optional<Snapshot> current() {
        return enabled() ? Optional.ofNullable(snapshot) : Optional.empty();
    }

    private boolean enabled() {
        return config.isEnabled() && config.getNightly().isEnabled();
    }

    /** Every enabled workspace, 0 unless counted: the series exists before its first problem. */
    private List<Series> perWorkspace(List<String> enabled, List<TopicProjectNightDAO.WorkspaceCount> counts) {
        Map<String, Long> byWorkspace = counts.stream().collect(Collectors.toMap(
                TopicProjectNightDAO.WorkspaceCount::workspaceId, TopicProjectNightDAO.WorkspaceCount::projects));
        return enabled.stream()
                .map(workspaceId -> Series.builder()
                        .attributes(Attributes.of(WORKSPACE_ID, workspaceId, WORKSPACE_NAME, names.apply(workspaceId)))
                        .value(byWorkspace.getOrDefault(workspaceId, 0L))
                        .build())
                .toList();
    }

    private double secondsSince(Instant from) {
        return Math.max(0, Duration.between(from, clock.instant()).toMillis() / 1e3);
    }
}
