package com.comet.opik.domain.ailabels;

import com.comet.opik.domain.ailabels.AiLabelItemPlan.JobKey;
import com.comet.opik.domain.ailabels.AiLabelSweepDAO.Candidate;
import com.comet.opik.domain.ailabels.AiLabelSweepDAO.SweepTask;
import com.comet.opik.infrastructure.AiLabelsConfig;
import com.comet.opik.infrastructure.inference.BatchSubmitResponse;
import com.comet.opik.infrastructure.inference.InferenceClient;
import com.comet.opik.infrastructure.inference.InferenceErrorCode;
import com.comet.opik.infrastructure.inference.InferenceException;
import com.comet.opik.infrastructure.inference.JobView;
import com.comet.opik.infrastructure.lock.LockService;
import com.google.common.collect.Lists;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.comet.opik.infrastructure.lock.LockService.Lock;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * One ai-labels tick (design §6, spec 10): poll every in-flight job in one call and load the finished ones, sweep, then
 * submit within the global in-flight budget in shuffled turns across workspaces. Runs on the leader only.
 *
 * <p>The leader can change between ticks, so nothing a later tick relies on is kept in memory: in-flight work is in
 * MySQL, candidates are submitted in the tick that swept them (the next sweep finds whatever is left), and the sweep,
 * health and housekeeping cadences are cluster-wide locks held until they expire. Only the per-workspace 429 backoff is
 * local, and losing it costs at most one more 429.
 *
 * <p>No tick outlives the leader lease, so two pods never run one at once: every opik-inference call stops at its
 * phase's deadline, and once a submit fails with the service down (5xx or timeout) the tick tries no other job. Turns
 * stop at the tick budget; the jobs they filled may still be sent for one read timeout past it.
 */
@Slf4j
@Singleton
public class AiLabelsTickService {

    static final String JOB_LOST = "job_lost";
    static final String RENDER_OVER_BUDGET = "render_over_budget";
    static final String RENDER_ERROR = "render_error";
    static final String MISSING_RESULT = "missing_result";
    static final String OVER_BUDGET_LEVEL = "over_budget";

    // Shares of the tick budget at which each phase stops starting work: loading, then the sweep. Submit takes the rest.
    private static final double LOAD_SHARE = 0.4;
    private static final double SWEEP_SHARE = 0.7;

    private static final Duration DEFAULT_THROTTLE_BACKOFF = Duration.ofSeconds(30);
    private static final Duration LOADED_JOB_RETENTION = Duration.ofDays(7);
    private static final Duration HOUSEKEEPING_INTERVAL = Duration.ofDays(1);
    // Headroom for the request envelope around the items when filling a job up to maxJobBytes.
    private static final long JOB_ENVELOPE_BYTES = 4_096;

    private static final Lock SWEEP_LOCK = new Lock("ai_labels:sweep");
    private static final Lock HEALTH_LOCK = new Lock("ai_labels:health");
    private static final Lock HOUSEKEEPING_LOCK = new Lock("ai_labels:housekeeping");

    private final AiLabelsConfig config;
    private final TransactionTemplate template;
    private final InferenceClient client;
    private final LockService lockService;
    private final AiLabelSweepService sweepService;
    private final AiLabelTaskRegistry registry;
    private final AiLabelRenderer renderer;
    private final AiLabelItemBuilder itemBuilder;
    private final AiLabelResultMapper resultMapper;
    private final AiLabelStatusDAO statusDAO;
    private final AiLabelsDAO labelsDAO;

    private final Map<String, Instant> throttledUntil = new ConcurrentHashMap<>();
    private final AtomicLong inFlight = new AtomicLong();
    private long sweeps;

    private final LongCounter itemsSubmitted;
    private final LongCounter itemsDone;
    private final LongCounter itemsFailed;
    private final LongCounter throttled;
    private final LongCounter rejected;
    private final LongCounter orphanSuspected;
    private final LongCounter sweepOverrun;
    private final LongCounter compactionLevels;
    private final LongCounter tracesRendered;
    private final LongCounter tracesAdmitted;
    private final LongHistogram renderedBytes;
    private final LongHistogram historyTurns;
    private final LongHistogram historyBytes;
    private final DoubleHistogram jobAge;
    private final DoubleHistogram phaseDuration;

    @Inject
    public AiLabelsTickService(@NonNull @Config("aiLabels") AiLabelsConfig config,
            @NonNull TransactionTemplate template, @NonNull InferenceClient client,
            @NonNull LockService lockService, @NonNull AiLabelSweepService sweepService,
            @NonNull AiLabelTaskRegistry registry, @NonNull AiLabelRenderer renderer,
            @NonNull AiLabelItemBuilder itemBuilder, @NonNull AiLabelResultMapper resultMapper,
            @NonNull AiLabelStatusDAO statusDAO, @NonNull AiLabelsDAO labelsDAO) {
        this.config = config;
        this.template = template;
        this.client = client;
        this.lockService = lockService;
        this.sweepService = sweepService;
        this.registry = registry;
        this.renderer = renderer;
        this.itemBuilder = itemBuilder;
        this.resultMapper = resultMapper;
        this.statusDAO = statusDAO;
        this.labelsDAO = labelsDAO;

        var meter = GlobalOpenTelemetry.get().getMeter("opik.ai_labels");
        meter.gaugeBuilder("ai_labels.budget.in_flight").ofLongs().setUnit("By")
                .setDescription("Cost of submitted, not yet loaded, jobs")
                .buildWithCallback(measurement -> measurement.record(inFlight.get()));
        meter.gaugeBuilder("ai_labels.budget.limit").ofLongs().setUnit("By")
                .setDescription("The global in-flight budget")
                .buildWithCallback(measurement -> measurement.record(config.getBudgetBytes()));
        this.itemsSubmitted = meter.counterBuilder("ai_labels.items.submitted").build();
        this.itemsDone = meter.counterBuilder("ai_labels.items.done").build();
        this.itemsFailed = meter.counterBuilder("ai_labels.items.failed").build();
        this.throttled = meter.counterBuilder("ai_labels.submit.throttled").build();
        this.rejected = meter.counterBuilder("ai_labels.submit.rejected")
                .setDescription("Submits rejected with 400: a renderer or task bug")
                .build();
        this.orphanSuspected = meter.counterBuilder("ai_labels.submit.orphan_suspected")
                .setDescription("Jobs accepted after the leader's lease ran out")
                .build();
        this.sweepOverrun = meter.counterBuilder("ai_labels.sweep.overrun")
                .setDescription("Sweeps that ran out of time before every bucket was swept")
                .build();
        this.compactionLevels = meter.counterBuilder("ai_labels.render.compaction_level").build();
        this.tracesRendered = meter.counterBuilder("ai_labels.render.traces")
                .setDescription("Traces rendered by submit turns; compare with ai_labels.submit.traces")
                .build();
        this.tracesAdmitted = meter.counterBuilder("ai_labels.submit.traces")
                .setDescription("Rendered traces admitted to the budget")
                .build();
        this.renderedBytes = meter.histogramBuilder("ai_labels.render.bytes").ofLongs().setUnit("By").build();
        this.historyTurns = meter.histogramBuilder("ai_labels.render.history_turns").ofLongs()
                .setDescription("Earlier turns left in a fitted history")
                .build();
        this.historyBytes = meter.histogramBuilder("ai_labels.render.history_bytes").ofLongs().setUnit("By").build();
        this.jobAge = meter.histogramBuilder("ai_labels.jobs.age_seconds").setUnit("s").build();
        this.phaseDuration = meter.histogramBuilder("ai_labels.tick.phase_seconds").setUnit("s")
                .setDescription("Duration of each tick phase: load, sweep, submit")
                .build();
    }

    /**
     * How long the leader lock must last: the tick budget, plus whichever runs longer past its end, a sweep query that
     * started just before it or the sends of the jobs its turns filled.
     */
    public Duration leaderLease() {
        var sweep = config.getSweep().getMaxExecutionTime().toJavaDuration();
        var send = config.getInference().getReadTimeout().toJavaDuration();
        return config.getTickBudget().toJavaDuration()
                .plus(sweep.compareTo(send) >= 0 ? sweep : send)
                .plusSeconds(5);
    }

    public void tick(@NonNull Instant start) {
        var budget = config.getTickBudget().toJavaDuration();
        timed("load", () -> pollAndLoad(start.plus(share(budget, LOAD_SHARE))));

        var tasks = registry.tasks();
        if (tasks.isEmpty()) {
            return;
        }
        var sweepTasks = tasks.stream().map(SweepTask::of).toList();
        long free = freeBudget();
        // With no room for even one item, a sweep would only find traces that can't go out.
        if (free >= config.getMinItemBytes()) {
            var candidates = whenDue(SWEEP_LOCK, config.getSweepInterval().toJavaDuration(),
                    () -> timed("sweep", () -> sweep(sweepTasks, start.plus(share(budget, SWEEP_SHARE)))))
                    .orElse(List.of());
            timed("submit", () -> submit(candidates, tasks, free, start, start.plus(budget)));
        }
        // After submitting, so the health query never eats the time this sweep's candidates need.
        whenDue(HEALTH_LOCK, config.getHealthInterval().toJavaDuration(),
                () -> sweepService.refreshHealth(sweepService.buckets(), sweepTasks).block());
        whenDue(HOUSEKEEPING_LOCK, HOUSEKEEPING_INTERVAL, this::deleteLoadedJobs);
    }

    private static Duration share(Duration budget, double share) {
        return Duration.ofMillis((long) (budget.toMillis() * share));
    }

    private <T> T timed(String phase, Supplier<T> action) {
        long start = System.nanoTime();
        try {
            return action.get();
        } finally {
            phaseDuration.record((System.nanoTime() - start) / 1e9, Attributes.of(stringKey("phase"), phase));
        }
    }

    private void timed(String phase, Runnable action) {
        timed(phase, () -> {
            action.run();
            return null;
        });
    }

    /** Runs {@code action} only if no pod has run it within {@code interval}. */
    private <T> Optional<T> whenDue(Lock lock, Duration interval, Supplier<T> action) {
        return Optional.ofNullable(lockService.bestEffortLock(lock, Mono.fromSupplier(action), Mono.empty(), interval,
                Duration.ZERO, true).block());
    }

    // --- 1. Poll and load ---

    /**
     * Polls every submitted job in groups of up to 1,000 per call, then loads the finished ones and the lost ones (left
     * out of the response) until {@code deadline} or {@code loadMaxRows}. Their rows are written as one insert per
     * table, and only then are the jobs marked loaded, so a crash in between means they load again.
     */
    void pollAndLoad(Instant deadline) {
        var jobs = template.inTransaction(READ_ONLY, handle -> handle.attach(AiLabelJobDAO.class).submitted());
        if (jobs.isEmpty()) {
            return;
        }
        var views = new HashMap<String, JobView>();
        try {
            for (var group : Lists.partition(jobs.stream().map(AiLabelJob::jobId).toList(),
                    InferenceClient.MAX_STATUS_JOB_IDS)) {
                client.getJobs(group, deadline).block().forEach(view -> views.put(view.jobId(), view));
            }
        } catch (InferenceException exception) {
            log.warn("ai-labels status call failed, code '{}'; loading nothing this tick", exception.getCode());
            return;
        }
        var lost = jobs.stream().filter(job -> !views.containsKey(job.jobId())).toList();
        var finished = jobs.stream()
                .filter(job -> views.containsKey(job.jobId()) && views.get(job.jobId()).status().isTerminal())
                .toList();

        var rows = new AtomicInteger();
        var loaded = new ArrayList<LoadedJob>();
        lost.forEach(job -> loaded.add(loseJob(job)));
        loaded.addAll(Flux.fromIterable(finished)
                .takeWhile(job -> Instant.now().isBefore(deadline) && rows.get() < config.getLoadMaxRows())
                .flatMap(job -> Mono.fromCallable(() -> loadJob(job, deadline))
                        .subscribeOn(Schedulers.boundedElastic())
                        .doOnNext(result -> rows.addAndGet(result.labels().size() + result.statuses().size()))
                        .onErrorResume(exception -> {
                            if (exception instanceof InferenceException inferenceException) {
                                log.warn("ai-labels failed to load job '{}', workspace '{}', code '{}'; loading it "
                                        + "again later", job.jobId(), job.workspaceId(), inferenceException.getCode());
                            } else {
                                log.error("ai-labels failed to load job '{}', workspace '{}'; loading it again later",
                                        job.jobId(), job.workspaceId(), exception);
                            }
                            return Mono.empty();
                        }), config.getLoadConcurrency())
                .collectList()
                .block());
        write(loaded);
    }

    private record LoadedJob(AiLabelJob job, List<AiLabel> labels, List<AiLabelStatus> statuses) {
    }

    private void write(List<LoadedJob> loaded) {
        if (loaded.isEmpty()) {
            return;
        }
        try {
            labelsDAO.insert(loaded.stream().flatMap(result -> result.labels().stream()).toList()).block();
            statusDAO.insert(loaded.stream().flatMap(result -> result.statuses().stream()).toList()).block();
        } catch (RuntimeException exception) {
            // Writes are idempotent, so the jobs stay submitted and load again next tick.
            log.error("ai-labels failed to write '{}' loaded jobs; loading them again next tick", loaded.size(),
                    exception);
            return;
        }
        var jobIds = loaded.stream().map(result -> result.job().jobId()).toList();
        var now = Instant.now();
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AiLabelJobDAO.class);
            dao.markLoaded(jobIds, now);
            dao.deleteItems(jobIds);
            return null;
        });
        for (var result : loaded) {
            countOutcomes(result.job().endpoint(), result.statuses());
            inFlight.addAndGet(-result.job().bytes());
            jobAge.record(Duration.between(result.job().submittedAt(), now).toMillis() / 1_000.0,
                    Attributes.of(stringKey("endpoint"), result.job().endpoint().getValue()));
        }
    }

    /** Pages through a finished job's results and maps them; an item it never reports is retryable. */
    private LoadedJob loadJob(AiLabelJob job, Instant deadline) {
        var jobItems = findItems(job);
        var planned = plannedItems(job, jobItems);
        var labels = new ArrayList<AiLabel>();
        var statuses = new ArrayList<AiLabelStatus>();
        var seen = new HashSet<String>();
        Integer cursor = 0;
        while (cursor != null) {
            JobView page;
            try {
                page = client.getJob(job.jobId(), cursor, true, deadline).block();
            } catch (InferenceException exception) {
                if (InferenceErrorCode.NOT_FOUND.getValue().equals(exception.getCode())) {
                    // Its results expired between the status call and this read.
                    return lost(job, jobItems);
                }
                throw exception;
            }
            for (var result : page.results()) {
                // The service's cursor can repeat items across pages.
                if (!seen.add(result.itemId())) {
                    continue;
                }
                var item = planned.get(result.itemId());
                if (item == null) {
                    continue;
                }
                var mapped = resultMapper.map(result, item.plan(), item.items());
                labels.addAll(mapped.labels());
                statuses.addAll(mapped.statuses());
            }
            cursor = page.nextCursor();
        }
        // An in-flight row no reported item covered (never reported, or of a task this pod doesn't know) is retried.
        var reported = planned.entrySet().stream()
                .filter(entry -> seen.contains(entry.getKey()))
                .flatMap(entry -> entry.getValue().items().stream())
                .collect(Collectors.toSet());
        jobItems.stream()
                .filter(row -> !reported.contains(row))
                .map(row -> row.failed(MISSING_RESULT, true))
                .forEach(statuses::add);
        return new LoadedJob(job, labels, statuses);
    }

    /** The job is gone or its results expired: its items become retryable failures, with the same attempt. */
    private LoadedJob loseJob(AiLabelJob job) {
        return lost(job, findItems(job));
    }

    private LoadedJob lost(AiLabelJob job, List<AiLabelJobItem> jobItems) {
        log.warn("ai-labels job '{}' not found, workspace '{}'; '{}' pairs will be retried", job.jobId(),
                job.workspaceId(), jobItems.size());
        return new LoadedJob(job, List.of(), jobItems.stream().map(row -> row.failed(JOB_LOST, true)).toList());
    }

    private List<AiLabelJobItem> findItems(AiLabelJob job) {
        return template.inTransaction(READ_ONLY,
                handle -> handle.attach(AiLabelJobDAO.class).findItems(List.of(job.jobId())));
    }

    private record PlannedItem(AiLabelItemPlan plan, List<AiLabelJobItem> items) {
    }

    /**
     * The job's items, rebuilt from its rows: a trace's items for one job are always submitted together, so planning
     * that trace's tasks in the job again gives the same items and ids.
     */
    private Map<String, PlannedItem> plannedItems(AiLabelJob job, List<AiLabelJobItem> rows) {
        var jobKey = new JobKey(job.endpoint(), job.cache());
        var items = new HashMap<String, PlannedItem>();
        rows.stream()
                .collect(Collectors.groupingBy(AiLabelJobItem::traceId))
                .forEach((traceId, traceRows) -> {
                    var rowsByTask = traceRows.stream()
                            .collect(Collectors.toMap(AiLabelJobItem::task, Function.identity()));
                    var tasks = rowsByTask.keySet().stream()
                            .map(registry::find)
                            .flatMap(Optional::stream)
                            .toList();
                    itemBuilder.plan(traceId, tasks).stream()
                            .filter(plan -> plan.jobKey().equals(jobKey))
                            .forEach(plan -> items.put(plan.itemId(), new PlannedItem(plan, plan.tasks().stream()
                                    .map(task -> rowsByTask.get(task.name()))
                                    .toList())));
                });
        return items;
    }

    private Integer deleteLoadedJobs() {
        return template.inTransaction(WRITE, handle -> handle.attach(AiLabelJobDAO.class)
                .deleteLoadedBefore(Instant.now().minus(LOADED_JOB_RETENTION)));
    }

    // --- 2. Sweep ---

    /**
     * Sweeps bucket by bucket until the deadline, starting one bucket later each time so that a slow sweep never
     * starves the same buckets. The first bucket always runs.
     */
    private List<Candidate> sweep(List<SweepTask> tasks, Instant deadline) {
        var buckets = sweepService.buckets();
        int offset = (int) Math.floorMod(sweeps++, buckets.size());
        var candidates = new ArrayList<Candidate>();
        for (int i = 0; i < buckets.size(); i++) {
            if (i > 0 && Instant.now().isAfter(deadline)) {
                sweepOverrun.add(1);
                log.warn("ai-labels sweep ran out of time after '{}' of '{}' buckets", i, buckets.size());
                break;
            }
            int index = (offset + i) % buckets.size();
            candidates.addAll(sweepService.sweep(buckets.get(index), index, tasks).collectList().block());
        }
        return candidates;
    }

    // --- 3. Submit ---

    private long freeBudget() {
        long inFlightNow = template.inTransaction(READ_ONLY,
                handle -> handle.attach(AiLabelJobDAO.class).inFlightBytes());
        inFlight.set(inFlightNow);
        return config.getBudgetBytes() - inFlightNow;
    }

    /** A rendered trace's items and what they cost against the budget together. */
    private record Admissible(Candidate candidate, List<AiLabelItem> items, long cost) {
    }

    private record Turn(long admitted, boolean done) {
    }

    /**
     * Shares the free budget out in passes over the workspaces, shuffled each tick so none is always first. In each
     * pass a workspace's turn gets {@code free ÷ workspaces left in the pass}, renders about as many traces as that
     * share can take, and admits whole traces within it. Admitted items fill a job per (workspace, job key), sent when
     * full and at the end.
     */
    void submit(List<Candidate> candidates, List<AiLabelTask> tasks, long free, Instant start, Instant deadline) {
        if (candidates.isEmpty()) {
            return;
        }
        var tasksByName = tasks.stream().collect(Collectors.toMap(AiLabelTask::name, Function.identity()));
        var queues = new LinkedHashMap<String, Deque<Candidate>>();
        // Item ids are trace ids, so one trace id in two of a workspace's projects can't share a job: the second waits.
        // It's left out of later sweeps while the first is in flight, and goes out after that loads.
        var seen = new HashSet<String>();
        candidates.stream()
                .filter(candidate -> seen.add(candidate.workspaceId() + "/" + candidate.traceId()))
                .forEach(candidate -> queues
                        .computeIfAbsent(candidate.workspaceId(), workspaceId -> new ArrayDeque<>())
                        .add(candidate));
        var ready = new HashMap<String, Deque<Admissible>>();
        // Turns stop at the deadline, and the jobs they filled are sent after it, so sends get one read timeout more.
        var jobs = new Jobs(start, deadline, deadline.plus(config.getInference().getReadTimeout().toJavaDuration()));

        var active = new ArrayList<>(queues.keySet());
        Collections.shuffle(active);
        while (!active.isEmpty() && free >= config.getMinItemBytes() && !jobs.serviceDown
                && Instant.now().isBefore(deadline)) {
            var pass = active.iterator();
            int left = active.size();
            while (pass.hasNext() && free >= config.getMinItemBytes() && !jobs.serviceDown
                    && Instant.now().isBefore(deadline)) {
                var workspaceId = pass.next();
                long share = free / left--;
                Turn turn;
                if (isThrottled(workspaceId)) {
                    turn = new Turn(0, true);
                } else {
                    try {
                        turn = turn(workspaceId, queues.get(workspaceId),
                                ready.computeIfAbsent(workspaceId, key -> new ArrayDeque<>()), share, free,
                                tasksByName, jobs);
                    } catch (RuntimeException exception) {
                        log.error("ai-labels failed a submit turn, workspace '{}'", workspaceId, exception);
                        turn = new Turn(0, true);
                    }
                }
                free -= turn.admitted();
                if (turn.done()) {
                    pass.remove();
                }
            }
        }
        jobs.sendAll();
        statusDAO.insert(jobs.failures).block();
        countOutcomes(null, jobs.failures);
    }

    /**
     * One workspace's turn: render a chunk if nothing rendered is waiting, then admit whole traces while they fit the
     * share. The turn's first trace needs only to fit {@code free}, so a trace bigger than any share still goes out.
     * Rendered traces that don't fit wait for the workspace's next turn; one that doesn't fit {@code free} ends the
     * workspace for this tick.
     */
    private Turn turn(String workspaceId, Deque<Candidate> queue, Deque<Admissible> ready, long share, long free,
            Map<String, AiLabelTask> tasksByName, Jobs jobs) {
        if (ready.isEmpty()) {
            long traces = Math.max(1, (share + config.getMinItemBytes() - 1) / config.getMinItemBytes());
            var chunk = new ArrayList<Candidate>();
            while (chunk.size() < Math.min(config.getChunkTraces(), traces) && !queue.isEmpty()) {
                chunk.add(queue.poll());
            }
            if (chunk.isEmpty()) {
                return new Turn(0, true);
            }
            tracesRendered.add(chunk.size());
            ready.addAll(renderChunk(workspaceId, chunk, tasksByName, jobs.failures, jobs.turnsEnd));
        }
        long admitted = 0;
        while (!ready.isEmpty()) {
            var next = ready.peek();
            if (admitted == 0 ? next.cost() > free : admitted + next.cost() > share) {
                break;
            }
            ready.poll();
            admitted += next.cost();
            tracesAdmitted.add(1);
            if (!jobs.add(workspaceId, next)) {
                return new Turn(admitted, true);
            }
        }
        boolean blocked = admitted == 0 && !ready.isEmpty();
        return new Turn(admitted, blocked || (ready.isEmpty() && queue.isEmpty()));
    }

    /**
     * What an item costs against the budget: its request bytes plus a charge per engine call, at least the floor.
     */
    private long cost(AiLabelItem item) {
        return Math.max(item.bytes() + config.getPerCallBytes() * item.engineCalls(), config.getMinItemBytes());
    }

    /**
     * Renders a chunk and builds its items, in candidate order. A trace rendered for both kinds of task (with and
     * without {@code {{history}}}) is admitted with all its items together. Traces that can't be labeled become
     * failures.
     */
    private List<Admissible> renderChunk(String workspaceId, List<Candidate> chunk,
            Map<String, AiLabelTask> tasksByName, List<AiLabelStatus> failures, Instant deadline) {
        var byTrace = chunk.stream().collect(Collectors.toMap(Candidate::traceId, Function.identity()));
        var refs = chunk.stream()
                .map(candidate -> new TraceRef(candidate.projectId(), candidate.traceId(), candidate.createdAt()))
                .toList();
        var itemsByTrace = new HashMap<UUID, List<AiLabelItem>>();
        for (var outcome : render(workspaceId, refs, failures, byTrace, tasksByName, deadline)) {
            var candidate = byTrace.get(outcome.trace().traceId());
            // The trace is rendered for each kind of task the chunk misses; this outcome serves only its own kind.
            var missing = missingTasks(candidate, tasksByName).stream()
                    .filter(task -> task.usesHistory() == outcome.withHistory())
                    .toList();
            if (missing.isEmpty()) {
                continue;
            }
            switch (outcome) {
                case RenderOutcome.OverBudget overBudget -> {
                    compactionLevels.add(1, Attributes.of(stringKey("level"), OVER_BUDGET_LEVEL));
                    failures.addAll(failedStatuses(workspaceId, candidate, missing, RENDER_OVER_BUDGET, false,
                            OVER_BUDGET_LEVEL));
                }
                case RenderOutcome.Rendered rendered -> {
                    compactionLevels.add(1, Attributes.of(stringKey("level"), levelOf(rendered.compactionLevel())));
                    renderedBytes.record(rendered.bytes());
                    if (rendered.withHistory()) {
                        historyTurns.record(rendered.history().size());
                        historyBytes.record(AiLabelJson.sizeOf(rendered.history()));
                    }
                    try {
                        itemsByTrace.computeIfAbsent(candidate.traceId(), key -> new ArrayList<>())
                                .addAll(itemBuilder.build(workspaceId, rendered, missing));
                    } catch (RuntimeException exception) {
                        // E.g. a span chain too deep to serialize: retried up to X attempts like any failure.
                        log.warn("ai-labels failed to build items, trace '{}', workspace '{}'",
                                candidate.traceId(), workspaceId, exception);
                        failures.addAll(failedStatuses(workspaceId, candidate, missing, RENDER_ERROR, true,
                                rendered.compactionLevel()));
                    }
                }
            }
        }
        return chunk.stream()
                .filter(candidate -> itemsByTrace.containsKey(candidate.traceId()))
                .map(candidate -> {
                    var items = itemsByTrace.get(candidate.traceId());
                    return new Admissible(candidate, items, items.stream().mapToLong(this::cost).sum());
                })
                .toList();
    }

    /**
     * Renders the chunk. If the chunk's read fails, its traces are rendered one at a time until {@code deadline}, so
     * that one bad trace can't hold back the rest; a trace that still fails becomes a retryable {@code render_error}.
     * Traces left when the deadline passes aren't in flight and have no failure, so a later sweep finds them.
     */
    private List<RenderOutcome> render(String workspaceId, List<TraceRef> refs, List<AiLabelStatus> failures,
            Map<UUID, Candidate> byTrace, Map<String, AiLabelTask> tasksByName, Instant deadline) {
        var tasks = refs.stream()
                .flatMap(ref -> missingTasks(byTrace.get(ref.traceId()), tasksByName).stream())
                .collect(Collectors.toSet());
        try {
            return renderer.render(workspaceId, refs, tasks).collectList().block();
        } catch (RuntimeException chunkError) {
            log.warn("ai-labels failed to render a chunk, workspace '{}', traces '{}'; rendering one at a time",
                    workspaceId, refs.size(), chunkError);
        }
        var outcomes = new ArrayList<RenderOutcome>();
        for (var ref : refs) {
            if (!Instant.now().isBefore(deadline)) {
                log.warn("ai-labels ran out of tick time rendering one at a time, workspace '{}', traces left '{}'",
                        workspaceId, refs.size() - refs.indexOf(ref));
                break;
            }
            try {
                outcomes.addAll(renderer.render(workspaceId, List.of(ref), tasks).collectList().block());
            } catch (RuntimeException exception) {
                log.warn("ai-labels failed to render trace '{}', workspace '{}'", ref.traceId(), workspaceId,
                        exception);
                var candidate = byTrace.get(ref.traceId());
                failures.addAll(failedStatuses(workspaceId, candidate, missingTasks(candidate, tasksByName),
                        RENDER_ERROR, true, ""));
            }
        }
        return outcomes;
    }

    /** One open job: its items, their traces' candidates (for the attempts), its request size and its cost. */
    private static final class JobBuffer {
        private final List<AiLabelItem> items = new ArrayList<>();
        private final Map<UUID, Candidate> candidates = new HashMap<>();
        private long requestBytes = JOB_ENVELOPE_BYTES;
        private long cost;
    }

    /** The tick's open jobs, one per (workspace, job key), and the failures to write at the end of submit. */
    private class Jobs {

        private final Instant start;
        private final Instant turnsEnd;
        private final Instant deadline;
        private final Map<String, Map<JobKey, JobBuffer>> open = new LinkedHashMap<>();
        private final List<AiLabelStatus> failures = new ArrayList<>();
        // A submit failed with the service down: the tick tries no other job, and the next tick tries again.
        private boolean serviceDown;

        Jobs(Instant start, Instant turnsEnd, Instant deadline) {
            this.start = start;
            this.turnsEnd = turnsEnd;
            this.deadline = deadline;
        }

        /**
         * Adds a trace's items, sending a job first if they'd overfill it. False once the workspace is throttled or the
         * service is down.
         */
        boolean add(String workspaceId, Admissible trace) {
            var byKey = trace.items().stream()
                    .collect(Collectors.groupingBy(item -> item.plan().jobKey(), LinkedHashMap::new,
                            Collectors.toList()));
            for (var entry : byKey.entrySet()) {
                var buffers = open.computeIfAbsent(workspaceId, key -> new LinkedHashMap<>());
                var buffer = buffers.computeIfAbsent(entry.getKey(), key -> new JobBuffer());
                // Plus one comma per item in the request's items array.
                long bytes = entry.getValue().stream().mapToLong(item -> item.bytes() + 1L).sum();
                if (!buffer.items.isEmpty()
                        && (buffer.items.size() + entry.getValue().size() > InferenceClient.MAX_BATCH_ITEMS
                                || buffer.requestBytes + bytes > config.getMaxJobBytes())) {
                    buffers.remove(entry.getKey());
                    if (!send(workspaceId, entry.getKey(), buffer)) {
                        return false;
                    }
                    buffer = buffers.computeIfAbsent(entry.getKey(), key -> new JobBuffer());
                }
                buffer.items.addAll(entry.getValue());
                buffer.candidates.put(trace.candidate().traceId(), trace.candidate());
                buffer.requestBytes += bytes;
                buffer.cost += entry.getValue().stream().mapToLong(AiLabelsTickService.this::cost).sum();
            }
            return true;
        }

        void sendAll() {
            for (var workspace : List.copyOf(open.entrySet())) {
                for (var job : List.copyOf(workspace.getValue().entrySet())) {
                    if (!send(workspace.getKey(), job.getKey(), job.getValue())) {
                        break;
                    }
                }
            }
            open.clear();
        }

        /**
         * False when the workspace is throttled, its other open jobs dropped, or when the service is down, every open
         * job dropped. A job that fails otherwise is dropped alone. A dropped job's traces aren't in flight, so a later
         * sweep finds them.
         */
        private boolean send(String workspaceId, JobKey jobKey, JobBuffer buffer) {
            if (serviceDown) {
                return false;
            }
            SubmitOutcome outcome;
            try {
                outcome = submitJob(workspaceId, jobKey, buffer, start, deadline, failures);
            } catch (RuntimeException exception) {
                log.error("ai-labels failed to submit a job, workspace '{}', items '{}'", workspaceId,
                        buffer.items.size(), exception);
                return true;
            }
            return switch (outcome) {
                case THROTTLED -> {
                    open.remove(workspaceId);
                    yield false;
                }
                case SERVICE_DOWN -> {
                    serviceDown = true;
                    yield false;
                }
                case ACCEPTED, DROPPED -> true;
            };
        }
    }

    private enum SubmitOutcome {
        ACCEPTED,
        THROTTLED,
        SERVICE_DOWN,
        DROPPED
    }

    /**
     * Submits one job, then records it and its items in one transaction. A crash after the 202 leaves a job nobody
     * loads, and the next sweep resubmits its traces.
     */
    private SubmitOutcome submitJob(String workspaceId, JobKey jobKey, JobBuffer buffer, Instant start,
            Instant deadline, List<AiLabelStatus> failures) {
        var items = buffer.items;
        BatchSubmitResponse response;
        try {
            response = switch (jobKey.endpoint()) {
                case DECIDE ->
                    client.submitDecide(itemBuilder.decideRequest(workspaceId, jobKey.cache(), items), deadline)
                            .block();
                case GENERATE ->
                    client.submitGenerate(itemBuilder.generateRequest(workspaceId, jobKey.cache(), items), deadline)
                            .block();
            };
        } catch (InferenceException exception) {
            return onSubmitError(workspaceId, jobKey, buffer, exception, failures);
        }

        if (Duration.between(start, Instant.now()).compareTo(leaderLease()) > 0) {
            orphanSuspected.add(1);
        }
        var job = AiLabelJob.builder()
                .jobId(response.jobId())
                .workspaceId(workspaceId)
                .endpoint(jobKey.endpoint())
                .cache(jobKey.cache())
                .itemCount(items.size())
                .bytes(buffer.cost)
                .status(AiLabelJob.Status.SUBMITTED)
                .submittedAt(Instant.now())
                .build();
        var jobItems = jobItems(response.jobId(), buffer);
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AiLabelJobDAO.class);
            dao.insert(job);
            dao.insertItems(jobItems);
            return null;
        });
        itemsSubmitted.add(items.size(), Attributes.of(stringKey("endpoint"), jobKey.endpoint().getValue()));
        inFlight.addAndGet(buffer.cost);
        return SubmitOutcome.ACCEPTED;
    }

    private static List<AiLabelJobItem> jobItems(String jobId, JobBuffer buffer) {
        return buffer.items.stream()
                .flatMap(item -> item.jobItems(jobId, AiLabelRenderer.RENDERER_VERSION,
                        nextAttempts(buffer.candidates.get(item.trace().traceId()))).stream())
                .toList();
    }

    private SubmitOutcome onSubmitError(String workspaceId, JobKey jobKey, JobBuffer buffer,
            InferenceException exception, List<AiLabelStatus> failures) {
        if (exception.isQuotaExceeded()) {
            var backoff = Optional.ofNullable(exception.getRetryAfter()).orElse(DEFAULT_THROTTLE_BACKOFF);
            throttledUntil.put(workspaceId, Instant.now().plus(backoff));
            throttled.add(1, Attributes.of(stringKey("workspace_id"), workspaceId));
            log.info("ai-labels submit throttled, workspace '{}', backing off '{}'", workspaceId, backoff);
            return SubmitOutcome.THROTTLED;
        }
        if (exception.isOutOfTime()) {
            log.info("ai-labels submit out of tick time, workspace '{}', items '{}'; the next sweep finds them again",
                    workspaceId, buffer.items.size());
            return SubmitOutcome.DROPPED;
        }
        if (exception.isRetryable()) {
            log.warn("ai-labels submit failed with opik-inference down, workspace '{}', code '{}', status '{}'; "
                    + "submitting nothing more this tick", workspaceId, exception.getCode(), exception.getHttpStatus());
            return SubmitOutcome.SERVICE_DOWN;
        }
        if (exception.getHttpStatus() == 400) {
            // One invalid item rejects the whole job: a renderer or task bug, never retried.
            rejected.add(1);
            log.error("ai-labels submit rejected, workspace '{}', endpoint '{}', items '{}', code '{}'", workspaceId,
                    jobKey.endpoint().getValue(), buffer.items.size(), exception.getCode());
            jobItems("", buffer).stream()
                    .map(row -> row.failed(exception.getCode(), false))
                    .forEach(failures::add);
            return SubmitOutcome.DROPPED;
        }
        log.warn("ai-labels submit failed, workspace '{}', items '{}', code '{}'; the next sweep finds them again",
                workspaceId, buffer.items.size(), exception.getCode());
        return SubmitOutcome.DROPPED;
    }

    private boolean isThrottled(String workspaceId) {
        var until = throttledUntil.get(workspaceId);
        if (until == null) {
            return false;
        }
        if (Instant.now().isBefore(until)) {
            return true;
        }
        throttledUntil.remove(workspaceId, until);
        return false;
    }

    private List<AiLabelTask> missingTasks(Candidate candidate, Map<String, AiLabelTask> tasksByName) {
        return candidate.missing().keySet().stream()
                .map(tasksByName::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private static Map<String, Integer> nextAttempts(Candidate candidate) {
        return candidate.missing().entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue() + 1));
    }

    private static List<AiLabelStatus> failedStatuses(String workspaceId, Candidate candidate,
            List<AiLabelTask> tasks, String errorCode, boolean retryable, String compactionLevel) {
        return tasks.stream()
                .map(task -> AiLabelStatus.builder()
                        .workspaceId(workspaceId)
                        .projectId(candidate.projectId())
                        .task(task.name())
                        .traceId(candidate.traceId())
                        .traceCreatedAt(candidate.createdAt())
                        .taskVersion(task.version())
                        .rendererVersion(AiLabelRenderer.RENDERER_VERSION)
                        .status(AiLabelStatus.Status.FAILED)
                        .retryable(retryable)
                        .attempt(candidate.missing().get(task.name()) + 1)
                        .errorCode(errorCode)
                        .jobId("")
                        .compactionLevel(compactionLevel)
                        .build())
                .toList();
    }

    /**
     * The level without its parameter ({@code output_truncated:4096} → {@code output_truncated}), to keep the metric
     * bounded.
     */
    private static String levelOf(String compactionLevel) {
        int colon = compactionLevel.indexOf(':');
        return colon < 0 ? compactionLevel : compactionLevel.substring(0, colon);
    }

    private void countOutcomes(AiLabelEndpoint endpoint, List<AiLabelStatus> statuses) {
        for (var status : statuses) {
            var attributes = endpoint == null
                    ? Attributes.of(stringKey("error_code"), status.errorCode())
                    : Attributes.of(stringKey("endpoint"), endpoint.getValue(), stringKey("error_code"),
                            status.errorCode());
            switch (status.status()) {
                case DONE -> itemsDone.add(1, attributes);
                case FAILED -> itemsFailed.add(1, attributes);
            }
        }
    }
}
