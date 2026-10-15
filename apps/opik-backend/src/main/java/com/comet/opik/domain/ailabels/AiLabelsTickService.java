package com.comet.opik.domain.ailabels;

import com.comet.opik.domain.ailabels.AiLabelItemPlan.JobKey;
import com.comet.opik.domain.ailabels.AiLabelSweepDAO.Candidate;
import com.comet.opik.domain.ailabels.AiLabelSweepDAO.SweepTask;
import com.comet.opik.infrastructure.AiLabelsConfig;
import com.comet.opik.infrastructure.inference.BatchSubmitResponse;
import com.comet.opik.infrastructure.inference.InferenceClient;
import com.comet.opik.infrastructure.inference.InferenceErrorCode;
import com.comet.opik.infrastructure.inference.InferenceException;
import com.comet.opik.infrastructure.lock.LockService;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.comet.opik.infrastructure.lock.LockService.Lock;
import static io.opentelemetry.api.common.AttributeKey.stringKey;

/**
 * One ai-labels tick (design §6): poll and load finished jobs, sweep when due, then submit within the global in-flight
 * budget, round-robin across workspaces. Runs on the leader only.
 *
 * <p>The leader can change between ticks, so nothing a later tick relies on is kept in memory: candidates are submitted
 * in the tick that swept them (the next sweep finds whatever is left), and the sweep, health and housekeeping cadences
 * are cluster-wide locks held until they expire. Only the per-workspace 429 backoff is local, and losing it costs at
 * most one more 429.
 */
@Slf4j
@Singleton
public class AiLabelsTickService {

    static final String JOB_LOST = "job_lost";
    static final String RENDER_OVER_BUDGET = "render_over_budget";
    static final String RENDER_ERROR = "render_error";
    static final String MISSING_RESULT = "missing_result";
    static final String OVER_BUDGET_LEVEL = "over_budget";

    private static final int POLL_LIMIT = 200;
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
    private final LongHistogram renderedBytes;
    private final DoubleHistogram jobAge;

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
        meter.gaugeBuilder("ai_labels.budget.in_flight").ofLongs()
                .setDescription("Engine calls in submitted, not yet loaded, jobs")
                .buildWithCallback(measurement -> measurement.record(inFlight.get()));
        meter.gaugeBuilder("ai_labels.budget.limit").ofLongs()
                .setDescription("The global in-flight engine-call budget")
                .buildWithCallback(measurement -> measurement.record(config.getBudgetEngineCalls()));
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
        this.renderedBytes = meter.histogramBuilder("ai_labels.render.bytes").ofLongs().setUnit("By").build();
        this.jobAge = meter.histogramBuilder("ai_labels.jobs.age_seconds").setUnit("s").build();
    }

    /** How long the leader lock must last: the tick budget, plus one sweep query that started just before its end. */
    public Duration leaderLease() {
        return config.getTickBudget().toJavaDuration()
                .plus(config.getSweep().getMaxExecutionTime().toJavaDuration())
                .plusSeconds(5);
    }

    public void tick(@NonNull Instant start) {
        var budget = config.getTickBudget().toJavaDuration();
        var deadline = start.plus(budget);
        poll(deadline);

        var tasks = registry.tasks();
        if (tasks.isEmpty()) {
            return;
        }
        var sweepTasks = tasks.stream().map(SweepTask::of).toList();
        var candidates = whenDue(SWEEP_LOCK, config.getSweepInterval().toJavaDuration(),
                () -> sweep(sweepTasks, start.plus(budget.dividedBy(2))))
                .orElse(List.of());
        submit(candidates, tasks, start, deadline);
        // After submitting, so the health query never eats the time this sweep's candidates need.
        whenDue(HEALTH_LOCK, config.getHealthInterval().toJavaDuration(),
                () -> sweepService.refreshHealth(sweepService.buckets(), sweepTasks).block());
        whenDue(HOUSEKEEPING_LOCK, HOUSEKEEPING_INTERVAL, this::deleteLoadedJobs);
    }

    /** Runs {@code action} only if no pod has run it within {@code interval}. */
    private <T> Optional<T> whenDue(Lock lock, Duration interval, Supplier<T> action) {
        return Optional.ofNullable(lockService.bestEffortLock(lock, Mono.fromSupplier(action), Mono.empty(), interval,
                Duration.ZERO, true).block());
    }

    // --- 1. Poll and load ---

    void poll(Instant deadline) {
        var jobs = template.inTransaction(READ_ONLY, handle -> handle.attach(AiLabelJobDAO.class)
                .due(config.getPollInterval().toJavaDuration(), POLL_LIMIT));
        for (var job : jobs) {
            if (Instant.now().isAfter(deadline)) {
                return;
            }
            try {
                pollJob(job);
            } catch (RuntimeException exception) {
                log.error("ai-labels failed to poll job '{}', workspace '{}'", job.jobId(), job.workspaceId(),
                        exception);
            }
        }
    }

    private void pollJob(AiLabelJob job) {
        try {
            var view = client.getJob(job.jobId(), 0, false).block();
            markPolled(job);
            if (view.status().isTerminal()) {
                loadJob(job);
            }
        } catch (InferenceException exception) {
            if (InferenceErrorCode.NOT_FOUND.getValue().equals(exception.getCode())) {
                loseJob(job);
            } else {
                markPolled(job);
                log.warn("ai-labels poll of job '{}' failed, code '{}'; polling again later", job.jobId(),
                        exception.getCode());
            }
        }
    }

    /**
     * Pages through a terminal job's results and writes, page by page, the labels and then the status rows, so a
     * {@code done} row always has its values. A crash only means the job is loaded again; the writes are idempotent.
     */
    private void loadJob(AiLabelJob job) {
        var pending = statusDAO.pendingTraceTasks(job.workspaceId(), job.jobId()).collectList().block();
        var items = itemsOf(job, pending);
        var seen = new HashSet<String>();
        Integer cursor = 0;
        while (cursor != null) {
            var page = client.getJob(job.jobId(), cursor, true).block();
            var labels = new ArrayList<AiLabel>();
            var statuses = new ArrayList<AiLabelStatus>();
            for (var result : page.results()) {
                // The service's cursor can repeat items across pages.
                if (!seen.add(result.itemId())) {
                    continue;
                }
                var item = items.get(result.itemId());
                if (item == null) {
                    continue;
                }
                var mapped = resultMapper.map(result, item.plan(), item.pending());
                labels.addAll(mapped.labels());
                statuses.addAll(mapped.statuses());
            }
            labelsDAO.insert(labels).block();
            statusDAO.insert(statuses).block();
            countOutcomes(job.endpoint(), statuses);
            cursor = page.nextCursor();
        }
        // A pending pair no reported item covered (never reported, or of a task this pod doesn't know) is retried now
        // rather than left pending until it counts as an orphan.
        var reported = items.entrySet().stream()
                .filter(entry -> seen.contains(entry.getKey()))
                .flatMap(entry -> entry.getValue().pending().stream())
                .collect(Collectors.toSet());
        var unreported = pending.stream()
                .filter(row -> !reported.contains(row))
                .map(row -> failed(row, MISSING_RESULT, true))
                .toList();
        statusDAO.insert(unreported).block();
        countOutcomes(job.endpoint(), unreported);
        markLoaded(job);
    }

    private record PendingItem(AiLabelItemPlan plan, List<AiLabelStatus> pending) {
    }

    /**
     * The job's items, rebuilt from its pending rows: a trace's items for one job are always submitted together, so
     * planning that trace's pending tasks again gives the same items and ids.
     */
    private Map<String, PendingItem> itemsOf(AiLabelJob job, List<AiLabelStatus> pending) {
        var jobKey = new JobKey(job.endpoint(), job.cache());
        var items = new HashMap<String, PendingItem>();
        pending.stream()
                .collect(Collectors.groupingBy(AiLabelStatus::traceId))
                .forEach((traceId, rows) -> {
                    var rowsByTask = rows.stream()
                            .collect(Collectors.toMap(AiLabelStatus::task, Function.identity()));
                    var tasks = rowsByTask.keySet().stream()
                            .map(registry::find)
                            .flatMap(Optional::stream)
                            .toList();
                    itemBuilder.plan(traceId, tasks).stream()
                            .filter(plan -> plan.jobKey().equals(jobKey))
                            .forEach(plan -> items.put(plan.itemId(), new PendingItem(plan, plan.tasks().stream()
                                    .map(task -> rowsByTask.get(task.name()))
                                    .toList())));
                });
        return items;
    }

    /** The job is gone or its results expired: its pending pairs become retryable failures, with the same attempt. */
    private void loseJob(AiLabelJob job) {
        var lost = statusDAO.pendingTraceTasks(job.workspaceId(), job.jobId())
                .map(row -> failed(row, JOB_LOST, true))
                .collectList()
                .block();
        statusDAO.insert(lost).block();
        countOutcomes(job.endpoint(), lost);
        markLoaded(job);
        log.warn("ai-labels job '{}' not found, workspace '{}'; '{}' pairs will be retried", job.jobId(),
                job.workspaceId(), lost.size());
    }

    private void markPolled(AiLabelJob job) {
        template.inTransaction(WRITE, handle -> handle.attach(AiLabelJobDAO.class).markPolled(job.jobId()));
    }

    private void markLoaded(AiLabelJob job) {
        var now = Instant.now();
        template.inTransaction(WRITE, handle -> handle.attach(AiLabelJobDAO.class).markLoaded(job.jobId(), now));
        jobAge.record(Duration.between(job.submittedAt(), now).toMillis() / 1_000.0,
                Attributes.of(stringKey("endpoint"), job.endpoint().getValue()));
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

    void submit(List<Candidate> candidates, List<AiLabelTask> tasks, Instant start, Instant deadline) {
        long inFlightNow = template.inTransaction(READ_ONLY,
                handle -> handle.attach(AiLabelJobDAO.class).inFlightEngineCalls());
        inFlight.set(inFlightNow);
        long free = config.getBudgetEngineCalls() - inFlightNow;
        if (candidates.isEmpty() || free <= 0) {
            return;
        }
        var tasksByName = tasks.stream().collect(Collectors.toMap(AiLabelTask::name, Function.identity()));
        var queues = new LinkedHashMap<String, Deque<Candidate>>();
        candidates.forEach(candidate -> queues
                .computeIfAbsent(candidate.workspaceId(), workspaceId -> new ArrayDeque<>())
                .add(candidate));

        // Round-robin: one chunk per workspace per turn, so a workspace with 10 candidates goes out on its first turn.
        while (free > 0 && !queues.isEmpty() && Instant.now().isBefore(deadline)) {
            var turns = queues.entrySet().iterator();
            while (turns.hasNext() && free > 0 && Instant.now().isBefore(deadline)) {
                var turn = turns.next();
                if (isThrottled(turn.getKey())) {
                    turns.remove();
                    continue;
                }
                var chunk = new ArrayList<Candidate>();
                while (chunk.size() < config.getChunkTraces() && !turn.getValue().isEmpty()) {
                    chunk.add(turn.getValue().poll());
                }
                if (turn.getValue().isEmpty()) {
                    turns.remove();
                }
                try {
                    free -= submitChunk(turn.getKey(), chunk, tasksByName, free, start);
                } catch (RuntimeException exception) {
                    log.error("ai-labels failed to submit a chunk, workspace '{}', traces '{}'", turn.getKey(),
                            chunk.size(), exception);
                }
            }
        }
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

    /**
     * Renders a chunk, builds its items and submits them as jobs. Returns the budget used up: the engine calls
     * submitted, or all of {@code free} once a trace's items didn't fit, so that the turns stop instead of rendering
     * traces that can't go out this tick.
     */
    private long submitChunk(String workspaceId, List<Candidate> chunk, Map<String, AiLabelTask> tasksByName,
            long free, Instant start) {
        var byTrace = chunk.stream().collect(Collectors.toMap(Candidate::traceId, Function.identity()));
        var refs = chunk.stream()
                .map(candidate -> new TraceRef(candidate.projectId(), candidate.traceId(), candidate.createdAt()))
                .toList();
        var failures = new ArrayList<AiLabelStatus>();
        // Per job key, each trace's items in candidate order: a trace's items for one job go out together.
        var traceItems = new LinkedHashMap<JobKey, List<List<AiLabelItem>>>();

        for (var outcome : render(workspaceId, refs, failures, byTrace, tasksByName)) {
            var candidate = byTrace.get(outcome.trace().traceId());
            var missing = missingTasks(candidate, tasksByName);
            switch (outcome) {
                case RenderOutcome.OverBudget overBudget -> {
                    compactionLevels.add(1, Attributes.of(stringKey("level"), OVER_BUDGET_LEVEL));
                    failures.addAll(failedStatuses(workspaceId, candidate, missing, RENDER_OVER_BUDGET, false,
                            OVER_BUDGET_LEVEL));
                }
                case RenderOutcome.Rendered rendered -> {
                    compactionLevels.add(1, Attributes.of(stringKey("level"), levelOf(rendered.compactionLevel())));
                    renderedBytes.record(rendered.bytes());
                    try {
                        itemBuilder.build(workspaceId, rendered, missing).stream()
                                .collect(Collectors.groupingBy(item -> item.plan().jobKey(), LinkedHashMap::new,
                                        Collectors.toList()))
                                .forEach((jobKey, items) -> traceItems
                                        .computeIfAbsent(jobKey, key -> new ArrayList<>())
                                        .add(items));
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
        statusDAO.insert(failures).block();
        countOutcomes(null, failures);

        long submitted = 0;
        boolean budgetFull = false;
        for (var entry : traceItems.entrySet()) {
            var job = new ArrayList<AiLabelItem>();
            long jobBytes = JOB_ENVELOPE_BYTES;
            long jobCalls = 0;
            for (var items : entry.getValue()) {
                long calls = items.stream().mapToLong(AiLabelItem::engineCalls).sum();
                // Plus one comma per item in the request's items array.
                long bytes = items.stream().mapToLong(item -> item.bytes() + 1L).sum();
                if (submitted + jobCalls + calls > free) {
                    budgetFull = true;
                    break;
                }
                if (!job.isEmpty() && (job.size() + items.size() > InferenceClient.MAX_BATCH_ITEMS
                        || jobBytes + bytes > config.getMaxJobBytes())) {
                    var outcome = submitJob(workspaceId, entry.getKey(), job, jobCalls, byTrace, start);
                    if (outcome == SubmitOutcome.THROTTLED) {
                        return submitted;
                    }
                    submitted += outcome == SubmitOutcome.ACCEPTED ? jobCalls : 0;
                    job = new ArrayList<>();
                    jobBytes = JOB_ENVELOPE_BYTES;
                    jobCalls = 0;
                }
                job.addAll(items);
                jobBytes += bytes;
                jobCalls += calls;
            }
            if (!job.isEmpty()) {
                var outcome = submitJob(workspaceId, entry.getKey(), job, jobCalls, byTrace, start);
                if (outcome == SubmitOutcome.THROTTLED) {
                    return submitted;
                }
                submitted += outcome == SubmitOutcome.ACCEPTED ? jobCalls : 0;
            }
        }
        return budgetFull ? free : submitted;
    }

    /**
     * Renders the chunk. If the chunk's read fails, its traces are rendered one at a time, so that one bad trace
     * can't hold back the rest; a trace that still fails becomes a retryable {@code render_error}.
     */
    private List<RenderOutcome> render(String workspaceId, List<TraceRef> refs, List<AiLabelStatus> failures,
            Map<UUID, Candidate> byTrace, Map<String, AiLabelTask> tasksByName) {
        try {
            return renderer.render(workspaceId, refs).collectList().block();
        } catch (RuntimeException chunkError) {
            log.warn("ai-labels failed to render a chunk, workspace '{}', traces '{}'; rendering one at a time",
                    workspaceId, refs.size(), chunkError);
        }
        var outcomes = new ArrayList<RenderOutcome>();
        for (var ref : refs) {
            try {
                outcomes.addAll(renderer.render(workspaceId, List.of(ref)).collectList().block());
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

    private enum SubmitOutcome {
        ACCEPTED,
        THROTTLED,
        DROPPED
    }

    /**
     * Submits one job, then records it and its pending rows. The order is submit, job row, pending rows: a crash
     * after the 202 leaves a job nobody polls, and the next sweep resubmits its traces.
     */
    private SubmitOutcome submitJob(String workspaceId, JobKey jobKey, List<AiLabelItem> items, long engineCalls,
            Map<UUID, Candidate> byTrace, Instant start) {
        BatchSubmitResponse response;
        try {
            response = switch (jobKey.endpoint()) {
                case DECIDE -> client.submitDecide(itemBuilder.decideRequest(workspaceId, jobKey.cache(), items))
                        .block();
                case GENERATE ->
                    client.submitGenerate(itemBuilder.generateRequest(workspaceId, jobKey.cache(), items)).block();
            };
        } catch (InferenceException exception) {
            return onSubmitError(workspaceId, jobKey, items, byTrace, exception);
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
                .engineCalls(Math.toIntExact(engineCalls))
                .status(AiLabelJob.Status.SUBMITTED)
                .submittedAt(Instant.now())
                .build();
        template.inTransaction(WRITE, handle -> {
            handle.attach(AiLabelJobDAO.class).insert(job);
            return null;
        });
        var pending = items.stream()
                .flatMap(item -> item.pendingStatuses(response.jobId(), AiLabelRenderer.RENDERER_VERSION,
                        nextAttempts(byTrace.get(item.trace().traceId()))).stream())
                .toList();
        statusDAO.insert(pending).block();
        itemsSubmitted.add(items.size(), Attributes.of(stringKey("endpoint"), jobKey.endpoint().getValue()));
        return SubmitOutcome.ACCEPTED;
    }

    private SubmitOutcome onSubmitError(String workspaceId, JobKey jobKey, List<AiLabelItem> items,
            Map<UUID, Candidate> byTrace, InferenceException exception) {
        if (exception.isQuotaExceeded()) {
            var backoff = Optional.ofNullable(exception.getRetryAfter()).orElse(DEFAULT_THROTTLE_BACKOFF);
            throttledUntil.put(workspaceId, Instant.now().plus(backoff));
            throttled.add(1, Attributes.of(stringKey("workspace_id"), workspaceId));
            log.info("ai-labels submit throttled, workspace '{}', backing off '{}'", workspaceId, backoff);
            return SubmitOutcome.THROTTLED;
        }
        if (exception.getHttpStatus() == 400) {
            // One invalid item rejects the whole job: a renderer or task bug, never retried.
            rejected.add(1);
            log.error("ai-labels submit rejected, workspace '{}', endpoint '{}', items '{}', code '{}'", workspaceId,
                    jobKey.endpoint().getValue(), items.size(), exception.getCode());
            var failures = items.stream()
                    .flatMap(item -> item.pendingStatuses("", AiLabelRenderer.RENDERER_VERSION,
                            nextAttempts(byTrace.get(item.trace().traceId()))).stream())
                    .map(row -> failed(row, exception.getCode(), false))
                    .toList();
            statusDAO.insert(failures).block();
            countOutcomes(jobKey.endpoint(), failures);
            return SubmitOutcome.DROPPED;
        }
        log.warn("ai-labels submit failed, workspace '{}', items '{}', code '{}'; the next sweep finds them again",
                workspaceId, items.size(), exception.getCode());
        return SubmitOutcome.DROPPED;
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

    private static AiLabelStatus failed(AiLabelStatus row, String errorCode, boolean retryable) {
        return row.toBuilder()
                .status(AiLabelStatus.Status.FAILED)
                .retryable(retryable)
                .errorCode(errorCode)
                .build();
    }

    /** The level without its parameter ({@code truncated:4096} → {@code truncated}), to keep the metric bounded. */
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
                case PENDING -> {
                }
            }
        }
    }
}
