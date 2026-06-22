package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.PromptType;
import com.comet.opik.api.Source;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.AutomationRuleEvaluator;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUserDefinedMetricPython;
import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import com.comet.opik.api.events.TraceToScoreUserDefinedMetricPython;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.api.events.TracesUpdated;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.evaluators.AutomationRuleEvaluatorService;
import com.comet.opik.domain.evaluators.OnlineScorePublisher;
import com.comet.opik.domain.evaluators.TraceFilterEvaluationService;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.log.LogContextAware;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.eventbus.Subscribe;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.slf4j.Logger;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.log.LogContextAware.wrapWithMdc;

/**
 * This service listens for Traces creation server in-memory event (via EventBus). When it happens, it fetches
 * Automation Rules for the trace's project and samples the trace batch for the proper scoring. The trace and code
 * (which can be a LLM-as-Judge, a Python code or new integrations we add) are enqueued in a Redis stream dedicated
 * to that evaluator type.
 */
@EagerSingleton
@Slf4j
public class OnlineScoringSampler {

    private static final String ONLINE_SCORING_NAMESPACE = "online_scoring";
    private static final AttributeKey<String> WORKSPACE_ID_KEY = AttributeKey.stringKey("workspace_id");
    private static final AttributeKey<String> EVALUATOR_TYPE_KEY = AttributeKey.stringKey("evaluator_type");
    private static final AttributeKey<String> DECISION_KEY = AttributeKey.stringKey("decision");
    // Sampling decision values (the per-workspace funnel between ingestion and scoring):
    private static final String DECISION_SAMPLED = "sampled"; // passed all checks -> enqueued for scoring
    private static final String DECISION_SKIPPED_DISABLED = "skipped_disabled";
    private static final String DECISION_SKIPPED_FILTER = "skipped_filter";
    private static final String DECISION_SKIPPED_SAMPLING = "skipped_sampling";

    private final AutomationRuleEvaluatorService ruleEvaluatorService;
    private final TraceFilterEvaluationService filterEvaluationService;
    private final TraceService traceService;
    private final ProjectService projectService;
    private final SecureRandom secureRandom;
    private final Logger userFacingLogger;
    private final ServiceTogglesConfig serviceTogglesConfig;
    private final OnlineScorePublisher onlineScorePublisher;
    private final LongCounter samplingDecisions;

    @Inject
    public OnlineScoringSampler(@NonNull @Config("serviceToggles") ServiceTogglesConfig serviceTogglesConfig,
            @NonNull AutomationRuleEvaluatorService ruleEvaluatorService,
            @NonNull TraceFilterEvaluationService filterEvaluationService,
            @NonNull OnlineScorePublisher onlineScorePublisher,
            @NonNull TraceService traceService,
            @NonNull ProjectService projectService) throws NoSuchAlgorithmException {
        this.ruleEvaluatorService = ruleEvaluatorService;
        this.filterEvaluationService = filterEvaluationService;
        this.onlineScorePublisher = onlineScorePublisher;
        this.serviceTogglesConfig = serviceTogglesConfig;
        this.traceService = traceService;
        this.projectService = projectService;
        secureRandom = SecureRandom.getInstanceStrong();
        userFacingLogger = UserFacingLoggingFactory.getLogger(OnlineScoringSampler.class);

        Meter meter = GlobalOpenTelemetry.getMeter(ONLINE_SCORING_NAMESPACE);
        this.samplingDecisions = meter.counterBuilder("online_scoring_sampler_decisions_total")
                .setDescription("Online-scoring sampling decisions, by workspace, evaluator type and outcome "
                        + "(sampled / skipped_disabled / skipped_filter / skipped_sampling)")
                .build();
    }

    private void recordDecision(String workspaceId, AutomationRuleEvaluator<?, ?> evaluator, String decision,
            long count) {
        samplingDecisions.add(count, Attributes.of(
                WORKSPACE_ID_KEY, workspaceId,
                EVALUATOR_TYPE_KEY, evaluator.getType().name(),
                DECISION_KEY, decision));
    }

    /**
     * Records a skip decision and emits the user-facing log line for it. Shared by the disabled,
     * filter-mismatch and sampling-skip branches of {@link #shouldSampleTrace} so a new skip reason
     * is a single call site rather than three. Always returns {@code false} so callers can
     * {@code return skip(...)}.
     */
    private boolean skip(String workspaceId, AutomationRuleEvaluator<?, ?> evaluator, Trace trace,
            String decision, String message, Object... args) {
        recordDecision(workspaceId, evaluator, decision, 1);
        // Important to set the workspaceId for logging purposes
        try (var logContext = createTraceLoggingContext(workspaceId, evaluator, trace)) {
            userFacingLogger.info(message, args);
        }
        return false;
    }

    /**
     * Listen for trace batches to check for existent Automation Rules to score them. It samples the trace batch and
     * enqueues the sample into Redis Stream.
     *
     * @param tracesBatch a traces batch with workspaceId and userName
     */
    @Subscribe
    public void onTracesCreated(TracesCreated tracesBatch) {
        // Filter out partial traces (no end_time) to avoid scoring incomplete data.
        // The SDK may send a "start" event (with input but no output/end_time) followed by
        // a "complete" event (with output and end_time). Only score complete traces.
        var completeTraces = tracesBatch.traces().stream()
                .filter(trace -> trace.endTime() != null)
                .toList();

        log.info("Received TracesCreated, complete '{}', total '{}', workspace '{}'",
                completeTraces.size(), tracesBatch.traces().size(), tracesBatch.workspaceId());

        sampleAndScore(completeTraces, tracesBatch.workspaceId(), tracesBatch.userName());
    }

    /**
     * Listen for trace updates that include end_time being set. This handles the case where
     * the SDK sends a POST (create) at function start and a PATCH (update) at function end
     * (e.g., manual trace.end() API). Without this, traces completed via PATCH would never
     * be scored because onTracesCreated only sees the initial partial trace.
     */
    @Subscribe
    public void onTracesUpdated(TracesUpdated event) {
        if (event.traceUpdate().endTime() == null) {
            log.debug("TracesUpdated event without endTime -> incomplete trace, won't score.");
            return;
        }

        log.info("Received TracesUpdated with end_time, traceIds '{}', workspace '{}'",
                event.traceIds().size(), event.workspaceId());

        // NOTE: there is a potential race condition in multi-node ClickHouse clusters — the write
        // may have landed on one replica while this read hits another that hasn't replicated yet.
        // In practice doOnSuccess fires after the INSERT completes and reads use FINAL, so this is
        // unlikely. If it becomes an issue, consider carrying the full Trace objects in the event.
        var traces = traceService.getByIds(new ArrayList<>(event.traceIds()))
                .filter(trace -> trace.endTime() != null)
                .collectList()
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, event.workspaceId())
                        .put(RequestContext.USER_NAME, event.userName()))
                .block();

        sampleAndScore(traces, event.workspaceId(), event.userName());
    }

    private void sampleAndScore(List<Trace> traces, String workspaceId, String userName) {
        if (CollectionUtils.isEmpty(traces)) {
            log.info("No traces to score for workspace '{}'", workspaceId);
            return;
        }

        // TraceDAO.findByIds (used by the onTracesUpdated path) populates projectId but not
        // projectName — the ClickHouse traces table doesn't carry the name. Downstream,
        // FeedbackScoreService.processScoreBatch groups by projectName and resolves projectId
        // from it, so a null name there causes every score to land in "Default Project".
        // Stamp the name back on, resolved once per project from MySQL, before publishing the
        // scoring event.
        traces = stampMissingProjectNames(traces, workspaceId);

        var tracesByProject = traces.stream().collect(Collectors.groupingBy(Trace::projectId));

        var countMap = tracesByProject.entrySet().stream()
                .collect(Collectors.toMap(entry -> "projectId: " + entry.getKey(),
                        entry -> entry.getValue().size()));

        log.info("Scoring traces, count '{}', workspace '{}', projects '{}'", traces.size(), workspaceId, countMap);

        // fetch automation rules per project
        tracesByProject.forEach((projectId, projectTraces) -> {
            // Only score traces from SDK logging source, by all applicable evaluators.
            // We deliberately do not read selected_rule_ids from SDK traces.
            // Non-SDK traces (playground, experiment, optimization) are only scored when
            // they carry selected_rule_ids metadata (explicit user selection from the playground).
            var scorableTraces = new ArrayList<Trace>();
            var selectedRuleIdsByTrace = new HashMap<UUID, Set<UUID>>();
            for (var trace : projectTraces) {
                if (Source.isLoggingSource(trace.source())) {
                    // For SDK traces all evaluators apply
                    scorableTraces.add(trace);
                } else {
                    var ruleIds = extractSelectedRuleIds(trace);
                    if (!ruleIds.isEmpty()) {
                        scorableTraces.add(trace);
                        selectedRuleIdsByTrace.put(trace.id(), ruleIds);
                    }
                }
            }
            if (scorableTraces.isEmpty()) {
                log.info(
                        "No scorable traces: source is not SDK and no selected_rule_ids, projectId '{}', workspaceId '{}'",
                        projectId, workspaceId);
                return;
            }

            log.info("Fetching evaluators, traces '{}', project '{}', workspace '{}'",
                    scorableTraces.size(), projectId, workspaceId);

            List<? extends AutomationRuleEvaluator<?, ?>> evaluators = ruleEvaluatorService.findAll(
                    projectId, workspaceId);

            //When using the MDC with multiple threads, we must ensure that the context is propagated. For this reason, we must use the wrapWithMdc method.
            evaluators.parallelStream().forEach(evaluator -> {
                // Samples traces for this rule.
                // If any trace carries explicit rule selections, filter evaluators to that set.
                // If no selection found, use all evaluators (default behavior for backward compatibility).
                var samples = scorableTraces.stream()
                        .filter(trace -> isEvaluatorSelectedForTrace(evaluator, trace, selectedRuleIdsByTrace))
                        .filter(trace -> shouldSampleTrace(evaluator, workspaceId, trace));
                switch (evaluator.getType()) {
                    case LLM_AS_JUDGE -> {
                        var messages = samples
                                .map(trace -> toLlmAsJudgeMessage(workspaceId, userName,
                                        (AutomationRuleEvaluatorLlmAsJudge) evaluator, trace))
                                .toList();
                        logSampledTrace(evaluator, messages, scorableTraces.size());
                        if (!messages.isEmpty()) {
                            recordDecision(workspaceId, evaluator, DECISION_SAMPLED, messages.size());
                            onlineScorePublisher.enqueueMessage(messages, AutomationRuleEvaluatorType.LLM_AS_JUDGE);
                        }
                    }
                    case USER_DEFINED_METRIC_PYTHON -> {
                        if (serviceTogglesConfig.isPythonEvaluatorEnabled()) {
                            var messages = samples
                                    .map(trace -> toScoreUserDefinedMetricPython(workspaceId, userName,
                                            (AutomationRuleEvaluatorUserDefinedMetricPython) evaluator, trace))
                                    .toList();
                            logSampledTrace(evaluator, messages, scorableTraces.size());
                            if (!messages.isEmpty()) {
                                recordDecision(workspaceId, evaluator, DECISION_SAMPLED, messages.size());
                                onlineScorePublisher.enqueueMessage(messages,
                                        AutomationRuleEvaluatorType.USER_DEFINED_METRIC_PYTHON);
                            }
                        } else {
                            log.warn("Python evaluator is disabled. Skipping sampling for evaluator type '{}'",
                                    evaluator.getType());
                        }
                    }
                    default -> log.warn("No process defined for evaluator type '{}'", evaluator.getType());
                }
            });
        });
    }

    /**
     * Returns the given trace list with each {@code projectName == null} entry rebuilt
     * with the name resolved from {@link ProjectService#findIdToNameByIds}. Entries that
     * already carry a projectName, and entries whose projectId isn't resolvable, pass
     * through unchanged — the latter logs a warning. We deliberately don't fail-fast on
     * an unresolved id: a transient lookup miss shouldn't drop scoring entirely; the
     * downstream {@code FeedbackScoreService} will fall back to Default Project via the
     * existing contract, and the warn log surfaces the issue for follow-up.
     */
    private List<Trace> stampMissingProjectNames(List<Trace> traces, String workspaceId) {
        Set<UUID> missingNameProjectIds = traces.stream()
                .filter(trace -> trace.projectName() == null)
                .map(Trace::projectId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (missingNameProjectIds.isEmpty()) {
            return traces;
        }
        Map<UUID, String> projectNamesById = projectService.findIdToNameByIds(
                workspaceId, missingNameProjectIds);
        return traces.stream()
                .map(trace -> {
                    if (trace.projectName() != null) {
                        return trace;
                    }
                    String resolved = projectNamesById.get(trace.projectId());
                    if (resolved == null) {
                        log.warn(
                                "Could not resolve projectName for projectId '{}' on traceId '{}' in workspace '{}';"
                                        + " scoring will proceed but the feedback score may not land on the expected project",
                                trace.projectId(), trace.id(), workspaceId);
                        return trace;
                    }
                    return trace.toBuilder().projectName(resolved).build();
                })
                .toList();
    }

    private boolean shouldSampleTrace(AutomationRuleEvaluator<?, ?> evaluator, String workspaceId, Trace trace) {
        // Check if rule is enabled first
        if (!evaluator.isEnabled()) {
            return skip(workspaceId, evaluator, trace, DECISION_SKIPPED_DISABLED,
                    "The traceId '{}' was skipped for rule: '{}' as the rule is disabled",
                    trace.id(), evaluator.getName());
        }

        // Check if trace matches all filters
        if (!filterEvaluationService.matchesAllFilters(evaluator.getFilters(), trace)) {
            return skip(workspaceId, evaluator, trace, DECISION_SKIPPED_FILTER,
                    "The traceId '{}' was skipped for rule: '{}' as it does not match the configured filters",
                    trace.id(), evaluator.getName());
        }

        if (secureRandom.nextFloat() >= evaluator.getSamplingRate()) {
            return skip(workspaceId, evaluator, trace, DECISION_SKIPPED_SAMPLING,
                    "The traceId '{}' was skipped for rule: '{}' and per the sampling rate '{}'",
                    trace.id(), evaluator.getName(), evaluator.getSamplingRate());
        }

        // The DECISION_SAMPLED metric is recorded at enqueue time (see sampleAndScore), so it
        // reflects messages actually published to Redis rather than the sampling roll alone.
        return true;
    }

    private TraceToScoreLlmAsJudge toLlmAsJudgeMessage(String workspaceId, String userName,
            AutomationRuleEvaluatorLlmAsJudge evaluator,
            Trace trace) {
        return TraceToScoreLlmAsJudge.builder()
                .trace(trace)
                .ruleId(evaluator.getId())
                .ruleName(evaluator.getName())
                .llmAsJudgeCode(evaluator.getCode())
                .workspaceId(workspaceId)
                .userName(userName)
                .scoreNameMapping(Map.of())
                .promptType(PromptType.MUSTACHE)
                .build();
    }

    private TraceToScoreUserDefinedMetricPython toScoreUserDefinedMetricPython(String workspaceId, String userName,
            AutomationRuleEvaluatorUserDefinedMetricPython evaluator,
            Trace trace) {
        return TraceToScoreUserDefinedMetricPython.builder()
                .trace(trace)
                .ruleId(evaluator.getId())
                .ruleName(evaluator.getName())
                .code(evaluator.getCode())
                .workspaceId(workspaceId)
                .userName(userName)
                .build();
    }

    private void logSampledTrace(AutomationRuleEvaluator<?, ?> evaluator, List<?> messages, int totalTraces) {
        log.info("[AutomationRule '{}', type '{}'] Sampled '{}/{}' from trace batch (expected rate: '{}')",
                evaluator.getName(),
                evaluator.getType(),
                messages.size(),
                totalTraces,
                evaluator.getSamplingRate());
    }

    private LogContextAware.Closable createTraceLoggingContext(String workspaceId,
            AutomationRuleEvaluator<?, ?> evaluator,
            Trace trace) {
        return wrapWithMdc(Map.of(
                UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name(),
                UserLog.WORKSPACE_ID, workspaceId,
                UserLog.RULE_ID, evaluator.getId().toString(),
                UserLog.TRACE_ID, trace.id().toString()));
    }

    /**
     * Decides whether the given evaluator applies to the given trace based on per-trace rule selection.
     * <ul>
     *   <li>SDK traces (and legacy null-source traces) are absent from {@code selectedRuleIdsByTrace}
     *       and always run against every evaluator.</li>
     *   <li>Non-SDK traces (e.g., Playground) that carry {@code selected_rule_ids} metadata are
     *       present in the map and only run against evaluators whose ID is in their own selection.</li>
     * </ul>
     *
     * @param evaluator              the evaluator being considered
     * @param trace                  the trace being sampled
     * @param selectedRuleIdsByTrace trace-id to selected rule IDs, populated only for non-SDK traces
     * @return true if the evaluator applies to the trace
     */
    private boolean isEvaluatorSelectedForTrace(AutomationRuleEvaluator<?, ?> evaluator, Trace trace,
            Map<UUID, Set<UUID>> selectedRuleIdsByTrace) {
        var selectedRuleIds = selectedRuleIdsByTrace.get(trace.id());
        return selectedRuleIds == null || selectedRuleIds.contains(evaluator.getId());
    }

    /**
     * Extracts selected_rule_ids from trace metadata.
     *
     * @param trace the trace to check
     * @return set of rule UUIDs found in metadata, or empty set if absent/invalid
     */
    private Set<UUID> extractSelectedRuleIds(Trace trace) {
        return Optional.ofNullable(trace.metadata())
                .map(metadata -> metadata.get("selected_rule_ids"))
                .filter(JsonNode::isArray)
                .map(ruleIdsNode -> {
                    Set<UUID> ruleIds = new HashSet<>();
                    try {
                        ruleIdsNode.forEach(idNode -> {
                            if (idNode.isTextual()) {
                                try {
                                    ruleIds.add(UUID.fromString(idNode.asText()));
                                } catch (IllegalArgumentException exception) {
                                    log.warn("Invalid UUID format in selected_rule_ids metadata for trace: '{}'",
                                            trace.id(), exception);
                                }
                            }
                        });
                    } catch (RuntimeException exception) {
                        log.warn("Error parsing selected_rule_ids metadata for trace: '{}'", trace.id(), exception);
                    }
                    return ruleIds;
                })
                .orElse(Set.of());
    }
}
