package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.TraceThreadSampling;
import com.comet.opik.api.evaluators.AutomationRuleEvaluator;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.events.TraceThreadsCreated;
import com.comet.opik.api.filter.Field;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.filter.TraceThreadField;
import com.comet.opik.api.filter.TraceThreadFilter;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.evaluators.AutomationRuleEvaluatorService;
import com.comet.opik.domain.evaluators.TraceThreadFilterEvaluationService;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.domain.threads.TraceThreadModel;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.log.LogContextAware;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static com.comet.opik.api.filter.TraceThreadField.*;
import static com.comet.opik.infrastructure.log.LogContextAware.wrapWithMdc;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.reducing;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

@EagerSingleton
@Slf4j
public class TraceThreadOnlineScoringSamplerListener {

    private static final Set<AutomationRuleEvaluatorType> SUPPORTED_EVALUATOR_TYPES = Set.of(
            AutomationRuleEvaluatorType.TRACE_THREAD_LLM_AS_JUDGE,
            AutomationRuleEvaluatorType.TRACE_THREAD_USER_DEFINED_METRIC_PYTHON);

    private final SecureRandom secureRandom;
    private final AutomationRuleEvaluatorService ruleEvaluatorService;
    private final TraceThreadFilterEvaluationService filterEvaluationService;
    private final Logger userFacingLogger;
    private final TraceThreadService traceThreadService;
    private final TraceService traceService;

    /**
     * Initializes a SecureRandom instance for trace thread processing.
     * This is used to determine if a trace thread should be sampled for online scoring.
     **/
    @Inject
    public TraceThreadOnlineScoringSamplerListener(
            @NonNull AutomationRuleEvaluatorService ruleEvaluatorService,
            @NonNull TraceThreadFilterEvaluationService filterEvaluationService,
            @NonNull TraceThreadService traceThreadService,
            @NonNull TraceService traceService) {
        this.ruleEvaluatorService = ruleEvaluatorService;
        this.filterEvaluationService = filterEvaluationService;
        this.traceThreadService = traceThreadService;
        this.traceService = traceService;
        this.userFacingLogger = UserFacingLoggingFactory.getLogger(TraceThreadOnlineScoringSamplerListener.class);
        try {
            this.secureRandom = SecureRandom.getInstanceStrong();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "Failed to initialize SecureRandom instance for trace thread processing", e);
        }
    }

    /**
     * Handles the online scoring sampling for trace threads.
     * This method is invoked when a trace thread is created.
     *
     * @param event the event containing the trace thread information
     */
    @Subscribe
    public void onTraceThreadOnlineScoringSampled(@NonNull TraceThreadsCreated event) {

        UUID projectId = event.projectId();
        Map<UUID, TraceThreadModel> traceThreadModelMap = event.traceThreadModels().stream()
                .collect(toMap(TraceThreadModel::id, Function.identity()));

        String workspaceId = event.workspaceId();

        log.info(
                "Received TraceThreadOnlineScoringSampled event for workspaceId: '{}', projectId: '{}', traceThreadModelIds: '{}'. Processing online scoring sampling",
                workspaceId, projectId, traceThreadModelMap.keySet());

        if (traceThreadModelMap.isEmpty()) {
            log.info(
                    "No trace thread model IDs provided for projectId: '{}', workspaceId: '{}'. Skipping online scoring sampling.",
                    projectId, workspaceId);
            return;
        }

        List<AutomationRuleEvaluator<?, ?>> rules = ruleEvaluatorService.findAll(projectId, workspaceId)
                .stream()
                .filter(evaluator -> SUPPORTED_EVALUATOR_TYPES.contains(evaluator.getType()))
                .collect(toList());

        if (rules.isEmpty()) {
            log.info(
                    "No automation rule evaluators found for projectId: '{}', workspaceId: '{}'. Skipping online scoring sampling.",
                    projectId, workspaceId);
            return;
        }

        List<TraceThreadSampling> samplingPerRule = sampleTraceThreads(traceThreadModelMap, rules, workspaceId);

        traceThreadService.updateThreadSampledValue(projectId, samplingPerRule)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, event.userName()))
                .thenReturn(traceThreadModelMap.size())
                .subscribe(
                        unused -> log.info(
                                "Successfully updated trace threadModelIds: '{}'  sampling values for projectId: '{}', workspaceId: '{}'",
                                samplingPerRule.stream().map(TraceThreadSampling::threadModelId).toList(), projectId,
                                workspaceId),
                        error -> {
                            log.error(
                                    "Failed to update trace thread sampling values for projectId: '{}', workspaceId: '{}'",
                                    projectId, workspaceId);
                            log.error("Error updating trace thread sampling values", error);
                        });

    }

    private List<TraceThreadSampling> sampleTraceThreads(Map<UUID, TraceThreadModel> traceThreadModelMap,
            List<AutomationRuleEvaluator<?, ?>> rules, String workspaceId) {
        return traceThreadModelMap.keySet()
                .parallelStream()
                .flatMap(traceThreadModelId -> {
                    log.info("Processing trace threadModelId: '{}' for online scoring sampling", traceThreadModelId);

                    return rules.stream()
                            .map(evaluator -> {
                                boolean shouldBeSampled = false;

                                // Check if rule is enabled first
                                if (!evaluator.isEnabled()) {
                                    try (var logContext = createThreadLoggingContext(workspaceId, evaluator,
                                            traceThreadModelId)) {
                                        userFacingLogger.info(
                                                "The threadModelId '{}' was skipped for rule: '{}' as the rule is disabled",
                                                traceThreadModelId, evaluator.getName());
                                    }
                                } else
                                    if (!shouldSampleTraceThread(evaluator,
                                            traceThreadModelMap.get(traceThreadModelId))) {
                                                try (var logContext = createThreadLoggingContext(workspaceId, evaluator,
                                                        traceThreadModelId)) {
                                                    userFacingLogger.info(
                                                            "The threadModelId '{}' was skipped for rule: '{}' as it does not match the filters",
                                                            traceThreadModelId, evaluator.getName());
                                                }
                                            } else {
                                                shouldBeSampled = secureRandom.nextDouble() < evaluator
                                                        .getSamplingRate();

                                                try (var logContext = createThreadLoggingContext(workspaceId, evaluator,
                                                        traceThreadModelId)) {
                                                    if (!shouldBeSampled) {
                                                        userFacingLogger.info(
                                                                "The threadModelId '{}' was skipped for rule: '{}' and per the sampling rate '{}'",
                                                                traceThreadModelId, evaluator.getName(),
                                                                evaluator.getSamplingRate());
                                                    } else {
                                                        userFacingLogger.info(
                                                                "The threadModelId '{}' will be sampled for rule: '{}' with sampling rate '{}'",
                                                                traceThreadModelId, evaluator.getName(),
                                                                evaluator.getSamplingRate());
                                                    }
                                                }
                                            }

                                return new TraceThreadSampling(traceThreadModelMap.get(traceThreadModelId),
                                        Map.of(evaluator.getId(), shouldBeSampled));
                            });
                })
                .sequential()
                .collect(groupingBy(TraceThreadSampling::threadModelId,
                        mapping(TraceThreadSampling::samplingPerRule,
                                reducing(new HashMap<>(), this::groupRuleSampling))))
                .entrySet()
                .stream()
                .map(sampling -> new TraceThreadSampling(traceThreadModelMap.get(sampling.getKey()),
                        sampling.getValue()))
                .toList();
    }

    private Map<UUID, Boolean> groupRuleSampling(Map<UUID, Boolean> acc, Map<UUID, Boolean> current) {
        acc.putAll(current);
        return acc;
    }

    /**
     * Determines if a thread should be sampled based on the rule's filters.
     * Converts TraceFilter objects to TraceThreadFilter objects and evaluates them.
     *
     * @param evaluator the automation rule evaluator containing filters
     * @param thread the thread to evaluate
     * @return true if the thread matches all filters and should be sampled, false otherwise
     */
    private boolean shouldSampleTraceThread(AutomationRuleEvaluator<?, ?> evaluator, TraceThreadModel thread) {
        // Filter to only TraceFilter instances since TraceFilterEvaluationService expects TraceFilter
        List<TraceFilter> traceFilters = (List<TraceFilter>) evaluator.getFilters();
        if (traceFilters.isEmpty()) {
            return true; // No filters means all threads should be sampled
        }

        // Convert TraceFilter to TraceThreadFilter
        List<TraceThreadFilter> threadFilters = convertFilters(traceFilters);

        // Evaluate filters against the thread
        return filterEvaluationService.matchesAllFilters(threadFilters, thread);
    }

    /**
     * Converts a list of TraceFilter objects to TraceThreadFilter objects.
     * Maps trace fields to equivalent thread fields where possible.
     */
    private List<TraceThreadFilter> convertFilters(List<TraceFilter> traceFilters) {
        return traceFilters.stream()
                .map(this::convertFilter)
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Converts a single TraceFilter to TraceThreadFilter.
     * Maps trace fields to equivalent thread fields.
     */
    private TraceThreadFilter convertFilter(TraceFilter traceFilter) {
        TraceThreadField threadField = convertFieldToThreadField(traceFilter.field());
        if (threadField == null) {
            log.warn("Cannot convert trace field '{}' to thread field, skipping filter", traceFilter.field());
            return null;
        }

        String value = traceFilter.value();

        // Convert duration values from seconds to milliseconds for proper comparison
        if (threadField == DURATION && value != null) {
            try {
                double seconds = Double.parseDouble(value);
                long milliseconds = (long) (seconds * 1000);
                log.info("Converting duration filter from {} seconds to {} milliseconds", seconds, milliseconds);
                value = String.valueOf(milliseconds);
            } catch (NumberFormatException e) {
                log.warn("Invalid duration value '{}' for thread filter conversion", value);
            }
        }

        return TraceThreadFilter.builder()
                .field(threadField)
                .operator(traceFilter.operator())
                .key(traceFilter.key())
                .value(value)
                .build();
    }

    /**
     * Maps TraceField to TraceThreadField where possible.
     * Returns null for fields that don't have thread equivalents.
     */
    private TraceThreadField convertFieldToThreadField(Field traceField) {
        return switch (traceField.toString()) {
            case "ID" -> ID;
            case "START_TIME" -> START_TIME;
            case "END_TIME" -> END_TIME;
            case "CREATED_AT" -> CREATED_AT;
            case "LAST_UPDATED_AT" -> LAST_UPDATED_AT;
            case "TAGS" -> TAGS;
            case "STATUS" -> STATUS;
            case "DURATION" -> DURATION;
            case "FEEDBACK_SCORES" -> FEEDBACK_SCORES;
            // Fields that don't have thread equivalents
            case "NAME", "INPUT", "OUTPUT", "INPUT_JSON", "OUTPUT_JSON", "METADATA",
                    "TOTAL_ESTIMATED_COST", "LLM_SPAN_COUNT", "USAGE_COMPLETION_TOKENS",
                    "USAGE_PROMPT_TOKENS", "USAGE_TOTAL_TOKENS", "THREAD_ID", "GUARDRAILS",
                    "VISIBILITY_MODE", "ERROR_INFO", "CUSTOM" -> {
                log.debug("Trace field '{}' has no equivalent in thread model", traceField);
                yield null;
            }
            default -> {
                log.warn("Unknown trace field '{}' for conversion to thread field", traceField);
                yield null;
            }
        };
    }

    private LogContextAware.Closable createThreadLoggingContext(String workspaceId,
            AutomationRuleEvaluator<?, ?> evaluator, UUID traceThreadModelId) {
        return wrapWithMdc(Map.of(
                UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name(),
                UserLog.WORKSPACE_ID, workspaceId,
                UserLog.RULE_ID, evaluator.getId().toString(),
                UserLog.THREAD_MODEL_ID, traceThreadModelId.toString()));
    }
}
