package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.AutomationRuleEvaluatorType;
import com.comet.opik.api.Trace;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.domain.AutomationRuleEvaluatorService;
import com.comet.opik.domain.ChatCompletionService;
import com.comet.opik.domain.FeedbackScoreService;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.Message;
import dev.ai4j.openai4j.chat.SystemMessage;
import dev.ai4j.openai4j.chat.UserMessage;
import jakarta.inject.Inject;
import lombok.Builder;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.StringSubstitutor;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

@EagerSingleton
@Slf4j
public class OnlineScoringEventListener {

    private final AutomationRuleEvaluatorService ruleEvaluatorService;
    private final ChatCompletionService aiProxyService;
    private final FeedbackScoreService feedbackScoreService;

    @Inject
    public OnlineScoringEventListener(EventBus eventBus,
            AutomationRuleEvaluatorService ruleEvaluatorService,
            ChatCompletionService aiProxyService,
            FeedbackScoreService feedbackScoreService) {
        this.ruleEvaluatorService = ruleEvaluatorService;
        this.aiProxyService = aiProxyService;
        this.feedbackScoreService = feedbackScoreService;
        eventBus.register(this);
    }

    /**
     * Listen for trace batches to check for existent Automation Rules to score them.
     *
     * Automation Rule registers the percentage of traces to score, how to score them and so on.
     *
     * @param tracesBatch a traces batch with workspaceId and userName
     */
    @Subscribe
    public void onTracesCreated(TracesCreated tracesBatch) {
        log.debug(tracesBatch.traces().toString());

        Map<UUID, List<Trace>> tracesByProject = tracesBatch.traces().stream()
                .collect(Collectors.groupingBy(Trace::projectId));

        Map<String, Integer> countMap = tracesByProject.entrySet().stream()
                .collect(Collectors.toMap(entry -> "projectId " + entry.getKey(),
                        entry -> entry.getValue().size()));

        log.debug("[OnlineScoring] Received traces for workspace '{}': {}", tracesBatch.workspaceId(), countMap);

        Random random = new Random(System.currentTimeMillis());

        // fetch automation rules per project
        tracesByProject.forEach((projectId, traces) -> {
            log.debug("[OnlineScoring] Fetching evaluators for {} traces, project '{}' on workspace '{}'",
                    traces.size(), projectId, tracesBatch.workspaceId());
            List<AutomationRuleEvaluatorLlmAsJudge> evaluators = ruleEvaluatorService.findAll(
                    projectId, tracesBatch.workspaceId(), AutomationRuleEvaluatorType.LLM_AS_JUDGE);
            log.info("[OnlineScoring] Found {} evaluators for project '{}' on workspace '{}'", evaluators.size(),
                    projectId, tracesBatch.workspaceId());

            // for each rule, sample traces and score them
            evaluators.forEach(evaluator -> traces.stream()
                    .filter(e -> random.nextFloat() < evaluator.getSamplingRate())
                    .forEach(trace -> score(trace, tracesBatch.workspaceId(), evaluator)));
        });
    }

    /**
     * Use AI Proxy to score the trace and store it as a FeedbackScore.
     * If the evaluator has multiple score definitions, it calls the LLM once per score definition.
     *
     * @param trace the trace to score
     * @param workspaceId the workspace the trace belongs
     * @param evaluator the automation rule to score the trace
     */
    private void score(Trace trace, String workspaceId, AutomationRuleEvaluatorLlmAsJudge evaluator) {
        // TODO prepare base request
        var baseRequestBuilder = ChatCompletionRequest.builder()
                .model(evaluator.getCode().model().name())
                .temperature(evaluator.getCode().model().temperature())
                .messages(renderMessages(trace, evaluator.getCode()))
                .build();

        // TODO: call AI Proxy and parse response into 1+ FeedbackScore

        // TODO: store FeedbackScores
    }

    /**
     * Render the rule evaluator message template using the values from an actual trace.
     *
     * As the rule my consist in multiple messages, we check each one of them for variables to fill.
     * Then we go through every variable template to replace them for the value from the trace.
     *
     * @param trace the trace with value to use to replace template variables
     * @param evaluatorCode the evaluator
     * @return
     */
    List<Message> renderMessages(Trace trace, AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode evaluatorCode) {
        // prepare the map of replacements to use in all messages, extracting the actual value from the Trace
        var parsedVariables = variableMapping(evaluatorCode.variables());
        var replacements = parsedVariables.stream().map(mapper -> {
            var traceSection = switch (mapper.traceSection) {
                case INPUT -> trace.input();
                case OUTPUT -> trace.output();
                case METADATA -> trace.metadata();
            };

            return mapper.toBuilder()
                    .valueToReplace(getPath(traceSection, mapper.jsonPath()))
                    .build();
        })
                .filter(mapper -> mapper.valueToReplace() != null)
                .collect(
                        Collectors.toMap(MessageVariableMapping::variableName, MessageVariableMapping::valueToReplace));

        // will convert all '{{key}}' into 'value'
        var templateRenderer = new StringSubstitutor(replacements, "{{", "}}");

        // render the message templates from evaluator rule
        return evaluatorCode.messages().stream()
                .map(templateMessage -> {
                    var renderedMessage = templateRenderer.replace(templateMessage.content());

                    return switch (templateMessage.role()) {
                        case USER -> UserMessage.from(renderedMessage);
                        case SYSTEM -> SystemMessage.from(renderedMessage);
                        default -> {
                            log.info("No mapping for message role type {}", templateMessage.role());
                            yield null;
                        }
                    };
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Parse evaluator\'s variable mapper into an usable list of
     *
     * @param evaluatorVariables a map with variables and a path into a trace input/output/metadata to replace
     * @return a parsed list of mappings, easier to use for the template rendering
     */
    List<MessageVariableMapping> variableMapping(Map<String, String> evaluatorVariables) {
        log.debug("Parsing: {}", evaluatorVariables);
        return evaluatorVariables.entrySet().stream()
                .map(mapper -> {
                    var templateVariable = mapper.getKey();
                    var tracePath = mapper.getValue();

                    var builder = MessageVariableMapping.builder().variableName(templateVariable);

                    if (tracePath.startsWith("input.")) {
                        builder.traceSection(TraceSection.INPUT)
                                .jsonPath(tracePath.substring("input.".length()));
                    } else if (tracePath.startsWith("output.")) {
                        builder.traceSection(TraceSection.OUTPUT)
                                .jsonPath(tracePath.substring("output.".length()));
                    } else if (tracePath.startsWith("metadata.")) {
                        builder.traceSection(TraceSection.METADATA)
                                .jsonPath(tracePath.substring("metadata.".length()));
                    } else {
                        log.info("Couldn't map trace path '{}' into a input/output/metadata path", tracePath);
                        return null;
                    }

                    return builder.build();
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    enum TraceSection {
        INPUT,
        OUTPUT,
        METADATA
    }
    @Builder(toBuilder = true)
    record MessageVariableMapping(TraceSection traceSection, String variableName, String jsonPath,
            String valueToReplace) {
    }

    String getPath(JsonNode rootNode, String path) {
        String[] parts = path.split("\\."); // Split by dot
        JsonNode currentNode = rootNode;

        for (String part : parts) {
            if (currentNode == null || currentNode.isMissingNode()) {
                log.info("Couldn't find json path '{}' in Trace section {}", path, rootNode);
                return null;
            }
            currentNode = currentNode.path(part);
        }

        return currentNode.textValue();
    }
}
