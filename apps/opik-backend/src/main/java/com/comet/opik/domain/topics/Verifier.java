package com.comet.opik.domain.topics;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.experimental.UtilityClass;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * The batched verify call of the naming step: every group the naming gave to a live topic goes into one LLM call,
 * groups largest first. An answer entry counts when its {@code pair} is an integer in range and its {@code same} is a
 * boolean. A pair without such an answer, or answered both ways, is not verified. A failed call fails the run.
 */
@UtilityClass
public class Verifier {

    static final int BASE_TOKENS = 60;
    static final int TOKENS_PER_PAIR = 25;

    /**
     * One batched verify call, then the verdict per pair index that got a valid answer. A bad answer verifies no pair.
     * A failed call (timeout or error) fails the run before the step goes on: read as "no pair verified", it would turn
     * sound assigns into new topics.
     */
    static NightStep<Map<Integer, Boolean>> ask(String tag, List<TopicPrompts.VerifyPair> prompts) {
        var request = LlmRequest.builder()
                .call(LlmCall.VERIFY)
                .tag(tag)
                .prompt(TopicPrompts.verify(prompts))
                .schema(TopicPrompts.VERIFY_SCHEMA)
                .maxTokens(BASE_TOKENS + TOKENS_PER_PAIR * prompts.size())
                .build();
        return new NightStep.Ask<>(request, answer -> NightStep.done(verdicts(answer, prompts.size())));
    }

    private static Map<Integer, Boolean> verdicts(JsonNode answer, int pairs) {
        var answers = new HashMap<Integer, Boolean>();
        var conflicting = new HashSet<Integer>();
        for (var entry : NightJson.objects(answer == null ? null : answer.get("answers"))) {
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
