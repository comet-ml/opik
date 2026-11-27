package com.comet.opik.domain.topics;

import com.comet.opik.domain.ailabels.AiLabelTask;
import com.comet.opik.infrastructure.TopicsConfig.AlgorithmConfig;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.experimental.UtilityClass;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The batched verify call of the naming step ({@link TopicTasks#verify()}, whose {@code max_tokens} is the base the
 * call adds {@code nightlyVerifyTokensPerPair} per pair to): every group the naming gave to a live topic goes into one LLM call,
 * groups largest first. An answer entry counts when its {@code pair} is an integer in range and its {@code same} is a
 * boolean. A pair without such an answer, or answered both ways, is not verified.
 */
@UtilityClass
class Verifier {

    /**
     * One batched verify call, then {@code next} with the verdict per pair index that got a valid answer. A failed call
     * (timeout or error) and an answer that is not a JSON object give no verdicts at all and go to {@code failed}: read
     * as "no pair verified", they would send sound assigns on to the new-topic call.
     */
    static <T> NightStep<T> ask(String tag, List<TopicPrompts.VerifyPair> prompts, AiLabelTask task,
            AlgorithmConfig config, Function<Map<Integer, Boolean>, NightStep<T>> next,
            Supplier<NightStep<T>> failed) {
        var request = LlmRequest.builder()
                .call(LlmCall.VERIFY)
                .tag(tag)
                .prompt(TopicPrompts.verify(task, prompts, config.getVerifyTopicExamples(),
                        config.getVerifyGroupExamples()))
                .schema(task.schema())
                // Not capped here: a cut answer would verify no pair and turn sound assigns into new topics. A night
                // has at most nightlyMaxGroups pairs, and KMeansTopicAlgorithm checks that budget at startup.
                .maxTokens(maxTokens(task, config, prompts.size()))
                .build();
        return NightStep.ask(request,
                answer -> answer == null ? failed.get() : next.apply(verdicts(answer, prompts.size())), failed);
    }

    /** The verify call's max_tokens for {@code pairs} pairs: the task's base plus the config's tokens per pair. */
    static int maxTokens(AiLabelTask task, AlgorithmConfig config, int pairs) {
        return task.effectiveMaxTokens() + config.getNightlyVerifyTokensPerPair() * pairs;
    }

    private static Map<Integer, Boolean> verdicts(JsonNode answer, int pairs) {
        var answers = new HashMap<Integer, Boolean>();
        var conflicting = new HashSet<Integer>();
        for (var entry : NightJson.objects(answer.get("answers"))) {
            var pair = NightJson.integer(entry.get("pair"));
            var same = entry.get("same");
            if (pair != null && pair >= 0 && pair < pairs && same != null && same.isBoolean()) {
                var previous = answers.putIfAbsent(pair, same.booleanValue());
                if (previous != null && previous != same.booleanValue()) {
                    conflicting.add(pair);
                }
            }
        }
        conflicting.forEach(answers::remove);
        return answers;
    }
}
