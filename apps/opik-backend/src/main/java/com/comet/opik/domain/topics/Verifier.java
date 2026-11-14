package com.comet.opik.domain.topics;

import lombok.experimental.UtilityClass;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The batched verify call of the naming step: every group the naming gave to a live topic goes into one Qwen call,
 * groups largest first. An answer entry counts when its {@code pair} is an integer in range and its {@code same} is a
 * boolean. A pair without such an answer is not verified. A failed call fails the run.
 */
@UtilityClass
public class Verifier {

    static final int BASE_TOKENS = 60;
    static final int TOKENS_PER_PAIR = 25;

    /**
     * One batched verify call: the verdict per pair index that got a valid answer. A failed call (timeout or error)
     * throws, and the run fails: read as "no pair verified", it would turn sound assigns into new topics.
     */
    static Map<Integer, Boolean> ask(String tag, List<TopicPrompts.VerifyPair> prompts, NightLlm llm) {
        var answers = new HashMap<Integer, Boolean>();
        var answer = llm.generate(tag, TopicPrompts.verify(prompts), TopicPrompts.VERIFY_SCHEMA,
                BASE_TOKENS + TOKENS_PER_PAIR * prompts.size());
        for (var entry : NightJson.objects(answer == null ? null : answer.get("answers"))) {
            var pair = NightJson.integer(entry.get("pair"));
            var same = entry.get("same");
            if (pair != null && pair >= 0 && pair < prompts.size() && same != null && same.isBoolean()) {
                answers.put(pair, same.booleanValue());
            }
        }
        return answers;
    }
}
