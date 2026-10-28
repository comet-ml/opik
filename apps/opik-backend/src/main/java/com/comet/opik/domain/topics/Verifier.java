package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.GroupSummaries.GroupSummary;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Step h of the nightly update: every candidate group the revise apply mapped to a live topic (topics born tonight
 * included) goes into one batched Qwen call, candidates largest first. An answer entry counts when its {@code pair} is
 * an integer in range; its {@code same} is read with Python's truth rules (missing = true). A pair without a valid
 * answer is not verified. A failed call leaves every pair not verified and the night goes on.
 */
@Slf4j
@UtilityClass
public class Verifier {

    static final String TAG = "verify";
    static final int BASE_TOKENS = 60;
    static final int TOKENS_PER_PAIR = 25;

    /**
     * @param ordered the candidates largest first (ties by group id)
     * @param topics  the live topics as the prompt shows them, by topic index
     * @param map     the group map after the revise apply
     */
    public static Verified verify(@NonNull List<GroupSummary> ordered,
            @NonNull Map<Integer, TopicPrompts.PromptTopic> topics,
            @NonNull Map<Integer, Integer> map, @NonNull NightLlm llm) {
        var pairs = new ArrayList<int[]>();
        var prompts = new ArrayList<TopicPrompts.VerifyPair>();
        for (var group : ordered) {
            var topic = map.get(group.group());
            if (topic != null && topics.containsKey(topic)) {
                pairs.add(new int[]{group.group(), topic});
                prompts.add(new TopicPrompts.VerifyPair(topics.get(topic), group));
            }
        }
        var verdicts = new HashMap<Integer, Boolean>();
        if (pairs.isEmpty()) {
            return Verified.builder().pairs(List.of()).verdicts(verdicts).called(false).failed(false).build();
        }
        boolean failed = false;
        try {
            var answer = llm.generate(TAG, TopicPrompts.verify(prompts), TopicPrompts.VERIFY_SCHEMA,
                    BASE_TOKENS + TOKENS_PER_PAIR * pairs.size());
            for (var entry : ReviseApplier.objects(answer == null ? null : answer.get("answers"))) {
                var pair = ReviseApplier.integer(entry.get("pair"));
                if (pair != null && pair >= 0 && pair < pairs.size()) {
                    verdicts.put(pairs.get(pair)[0], truthy(entry.get("same")));
                }
            }
        } catch (TopicsRunException exception) {
            // The call failed (timeout or error): the run fails and the project keeps yesterday's list. Read as "no
            // pair verified", it would turn revise's sound assigns into forced births.
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("topics verify answer unreadable, no pair is verified", exception);
            verdicts.clear();
            failed = true;
        }
        return Verified.builder().pairs(List.copyOf(pairs)).verdicts(verdicts).called(true).failed(failed).build();
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

    /**
     * The pairs sent (group, topic index), in prompt order, and the verdict per group that got a valid answer.
     */
    @Builder
    public record Verified(@NonNull List<int[]> pairs, @NonNull Map<Integer, Boolean> verdicts, boolean called,
            boolean failed) {

        public boolean verified(int group) {
            return verdicts.getOrDefault(group, false);
        }
    }
}
