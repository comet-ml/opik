package com.comet.opik.domain.topics;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The batched verify call of the naming step: every group the naming gave to a live topic goes into one Qwen call,
 * groups largest first. An answer entry counts when its {@code pair} is an integer in range; its {@code same} is read
 * with Python's truth rules (missing = true). A pair without a valid answer is not verified. A failed call fails the
 * run.
 */
@Slf4j
@UtilityClass
public class Verifier {

    static final int BASE_TOKENS = 60;
    static final int TOKENS_PER_PAIR = 25;

    /**
     * One batched verify call: the verdict per pair index that got a valid answer, or null for an unreadable answer. A
     * failed call (timeout or error) throws, and the run fails: read as "no pair verified", it would turn sound assigns
     * into new topics.
     */
    static Map<Integer, Boolean> ask(String tag, List<TopicPrompts.VerifyPair> prompts, NightLlm llm) {
        var answers = new HashMap<Integer, Boolean>();
        try {
            var answer = llm.generate(tag, TopicPrompts.verify(prompts), TopicPrompts.VERIFY_SCHEMA,
                    BASE_TOKENS + TOKENS_PER_PAIR * prompts.size());
            for (var entry : NightJson.objects(answer == null ? null : answer.get("answers"))) {
                var pair = NightJson.integer(entry.get("pair"));
                if (pair != null && pair >= 0 && pair < prompts.size()) {
                    answers.put(pair, truthy(entry.get("same")));
                }
            }
        } catch (TopicsRunException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("topics {} answer unreadable, no pair is verified", tag, exception);
            return null;
        }
        return answers;
    }

    /** Python's {@code bool(value)} of a JSON value, true when it is missing. */
    static boolean truthy(JsonNode value) {
        if (value == null) {
            return true;
        }
        if (value.isBoolean()) {
            return value.booleanValue();
        }
        if (value.isNumber()) {
            return value.doubleValue() != 0;
        }
        if (value.isTextual()) {
            return !value.asText().isEmpty();
        }
        if (value.isContainerNode()) {
            return !value.isEmpty();
        }
        return false;
    }
}
