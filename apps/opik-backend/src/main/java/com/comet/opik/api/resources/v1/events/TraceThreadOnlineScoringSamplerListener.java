package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.TraceThreadSampling;
import com.comet.opik.api.evaluators.AutomationRuleEvaluator;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.events.TraceThreadsCreated;
import com.comet.opik.domain.evaluators.AutomationRuleEvaluatorService;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.auth.RequestContext;
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
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.log.LogContextAware.wrapWithMdc;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.reducing;
import static java.util.stream.Collectors.toList;

@EagerSingleton
@Slf4j
public class TraceThreadOnlineScoringSamplerListener {

    private static final Set<AutomationRuleEvaluatorType> SUPPORTED_EVALUATOR_TYPES = Set.of(
            AutomationRuleEvaluatorType.TRACE_THREAD_LLM_AS_JUDGE,
            AutomationRuleEvaluatorType.TRACE_THREAD_USER_DEFINED_METRIC_PYTHON);

    private final SecureRandom secureRandom;
    private final AutomationRuleEvaluatorService ruleEvaluatorService;
    private final Logger userFacingLogger;
    private final TraceThreadService traceThreadService;

    /**
     * Initializes a SecureRandom instance for trace thread processing.
     * This is used to determine if a trace thread should be sampled for online scoring.
     **/
    @Inject
    public TraceThreadOnlineScoringSamplerListener(
            @NonNull AutomationRuleEvaluatorService ruleEvaluatorService,
            @NonNull TraceThreadService traceThreadService) {
        this.ruleEvaluatorService = ruleEvaluatorService;
        this.traceThreadService = traceThreadService;
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
        List<UUID> traceThreadModelIds = event.traceThreadModelIds();
        String workspaceId = event.workspaceId();

        log.info(
                "Received TraceThreadOnlineScoringSampled event for workspace_id: '{}', projectId: '{}', traceThreadModelIds: '{}'. Processing online scoring sampling",
                workspaceId, projectId, traceThreadModelIds);

        if (traceThreadModelIds.isEmpty()) {
            log.info(
                    "No trace thread model IDs provided for projectId: '{}', workspaceId: '{}'. Skipping online scoring sampling.",
                    projectId, workspaceId);
            return;
        }

        List<AutomationRuleEvaluator<?>> rules = ruleEvaluatorService.findAll(projectId, workspaceId)
                .stream()
                .filter(evaluator -> SUPPORTED_EVALUATOR_TYPES.contains(evaluator.getType()))
                .collect(toList());

        if (rules.isEmpty()) {
            log.info(
                    "No automation rule evaluators found for projectId: '{}', workspaceId: '{}'. Skipping online scoring sampling.",
                    projectId, workspaceId);
            return;
        }

        List<TraceThreadSampling> samplingPerRule = sampleTraceThreads(traceThreadModelIds, rules, workspaceId);

        traceThreadService.updateThreadSampledValue(projectId, samplingPerRule)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, event.userName()))
                .subscribe(
                        unused -> log.info(
                                "Successfully updated trace thread: '[{}]'  sampling values for projectId: '{}', workspaceId: '{}'",
                                samplingPerRule.stream().map(TraceThreadSampling::threadModelId).toList(), projectId,
                                workspaceId),
                        error -> {
                            log.error(
                                    "Failed to update trace thread sampling values for projectId: '{}', workspaceId: '{}'",
                                    projectId, workspaceId);
                            log.error("Error updating trace thread sampling values", error);
                        });

    }

    private List<TraceThreadSampling> sampleTraceThreads(List<UUID> traceThreadModelIds,
            List<AutomationRuleEvaluator<?>> rules, String workspaceId) {
        return traceThreadModelIds
                .parallelStream()
                .flatMap(traceThreadModelId -> {
                    log.info("Processing trace thread model ID: '{}' for online scoring sampling", traceThreadModelId);

                    return rules.stream()
                            .map(evaluator -> {
                                boolean shouldBeSampled = secureRandom.nextDouble() < evaluator.getSamplingRate();

                                try (var logContext = wrapWithMdc(Map.of(
                                        UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name(),
                                        UserLog.WORKSPACE_ID, workspaceId,
                                        UserLog.RULE_ID, evaluator.getId().toString(),
                                        UserLog.THREAD_MODEL_ID, traceThreadModelId.toString()))) {

                                    if (!shouldBeSampled) {
                                        userFacingLogger.info(
                                                "The threadModelId '{}' was skipped for rule: '{}' and per the sampling rate '{}'",
                                                traceThreadModelId, evaluator.getName(), evaluator.getSamplingRate());
                                    } else {
                                        userFacingLogger.info(
                                                "The threadModelId '{}' will be sampled for rule: '{}' with sampling rate '{}'",
                                                traceThreadModelId, evaluator.getName(), evaluator.getSamplingRate());
                                    }
                                }

                                return new TraceThreadSampling(traceThreadModelId,
                                        Map.of(evaluator.getId(), shouldBeSampled));
                            });
                })
                .sequential()
                .collect(groupingBy(TraceThreadSampling::threadModelId,
                        mapping(TraceThreadSampling::samplingPerRule,
                                reducing(new HashMap<>(), this::groupRuleSampling))))
                .entrySet()
                .stream()
                .map(sampling -> new TraceThreadSampling(sampling.getKey(), sampling.getValue()))
                .toList();
    }

    private Map<UUID, Boolean> groupRuleSampling(Map<UUID, Boolean> acc, Map<UUID, Boolean> current) {
        acc.putAll(current);
        return acc;
    }
}
