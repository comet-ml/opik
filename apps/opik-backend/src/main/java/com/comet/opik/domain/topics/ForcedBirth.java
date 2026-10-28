package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.GroupSummaries.GroupSummary;
import com.comet.opik.domain.topics.NightState.RetiredReason;
import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Steps h and i of the nightly update after the verify call, as the stand's {@code verify_and_force} (v7): for each
 * candidate, largest first, a pair that was not verified is removed from the group map; then every big candidate that
 * ended without a topic (rejected, Other, or not mapped) gets one: below tightness {@value #MIN_TIGHTNESS} it is known
 * Other; on a full list, the topic not born tonight with the lowest 7-day share is retired when its share is below the
 * candidate's recent share, else the candidate is known Other; then one naming call, whose first entry's name gives a new
 * topic born tonight (an empty or catch-all name, an answer off the schema or a failed call make it known Other). A small
 * candidate goes no further: a rejected pair leaves it unmapped, an Other stays Other.
 */
@Slf4j
@UtilityClass
public class ForcedBirth {

    static final double MIN_TIGHTNESS = 0.80;
    static final String TAG_PREFIX = "naming_c";
    static final int NAMING_TOKENS = 2500;

    /**
     * @param ordered the candidates largest first (ties by group id)
     * @param big     the big candidates' group ids
     * @param topics  the live topics as the verify prompt showed them, by topic index (their 7-day shares)
     * @param map     the group map, changed in place
     */
    public static List<Outcome> apply(@NonNull NightState state, @NonNull List<GroupSummary> ordered,
            @NonNull Set<Integer> big, @NonNull Map<Integer, TopicPrompts.PromptTopic> topics,
            @NonNull Verifier.Verified verified, @NonNull Map<Integer, Integer> map, int maxTopics,
            @NonNull NightLlm llm) {
        var outcomes = new ArrayList<Outcome>();
        for (var candidate : ordered) {
            int group = candidate.group();
            var topic = map.get(group);
            if (topic != null && topic != ReviseApplier.OTHER && topics.containsKey(topic)) {
                if (verified.verified(group)) {
                    continue;
                }
                map.remove(group);
            } else if (topic != null && topic != ReviseApplier.OTHER) {
                continue;
            }
            if (!big.contains(group)) {
                continue;
            }
            if (candidate.tightness() < MIN_TIGHTNESS) {
                map.put(group, ReviseApplier.OTHER);
                outcomes.add(new Outcome(group, Result.OTHER_LOOSE, null));
                continue;
            }
            if (state.liveCount() >= maxTopics) {
                Integer lowest = null;
                for (int t : state.live()) {
                    if (state.topic(t).getBorn().equals(state.getNight())) {
                        continue;
                    }
                    if (lowest == null || recentShare(topics, t) < recentShare(topics, lowest)) {
                        lowest = t;
                    }
                }
                if (lowest == null || recentShare(topics, lowest) >= candidate.recentShare()) {
                    map.put(group, ReviseApplier.OTHER);
                    outcomes.add(new Outcome(group, Result.OTHER_FULL, null));
                    continue;
                }
                state.retire(lowest, RetiredReason.REPLACED);
            }
            var name = name(candidate, llm);
            if (name == null) {
                map.put(group, ReviseApplier.OTHER);
                outcomes.add(new Outcome(group, Result.OTHER_NAME, null));
                continue;
            }
            int born = state.born(name.name(), name.definition());
            map.put(group, born);
            outcomes.add(new Outcome(group, Result.BORN, born));
        }
        return outcomes;
    }

    private static double recentShare(Map<Integer, TopicPrompts.PromptTopic> topics, int topic) {
        var shown = topics.get(topic);
        return shown == null ? 0 : shown.recentShare();
    }

    /**
     * The naming call's first entry, or null for a blank or catch-all name or an unreadable answer. A failed call
     * throws.
     */
    private static AuthorApplier.NewTopic name(GroupSummary candidate, NightLlm llm) {
        try {
            var answer = llm.generate(TAG_PREFIX + candidate.group(), TopicPrompts.naming(candidate),
                    TopicPrompts.NAMING_SCHEMA, NAMING_TOKENS);
            var entries = answer == null ? null : answer.get("topics");
            if (entries == null || !entries.isArray() || entries.isEmpty() || !entries.get(0).isObject()) {
                return null;
            }
            var name = entries.get(0).get("name");
            var definition = entries.get(0).get("definition");
            if (name == null || !name.isTextual() || TopicText.pythonStrip(name.asText()).isEmpty()
                    || AuthorApplier.isCatchAll(name.asText())) {
                return null;
            }
            var text = definition != null && definition.isTextual() ? definition.asText() : "";
            return new AuthorApplier.NewTopic(TopicText.name(name.asText()), TopicText.pythonStrip(text),
                    List.of(candidate.group()));
        } catch (TopicsRunException exception) {
            // The call failed (timeout or error): the run fails, so a topic retired to make room is not lost.
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("topics naming answer unreadable for group '{}', it goes to known Other", candidate.group(),
                    exception);
            return null;
        }
    }

    public enum Result {
        BORN,
        OTHER_LOOSE,
        OTHER_FULL,
        OTHER_NAME
    }

    /** What happened to one big candidate left without a topic; {@code topic} is the index of the topic born. */
    @Builder
    public record Outcome(int group, @NonNull Result result, Integer topic) {
    }
}
