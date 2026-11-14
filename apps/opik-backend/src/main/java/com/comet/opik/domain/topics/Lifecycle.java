package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.NightState.RetiredReason;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

/**
 * Step f of the nightly update, after the final assignment. A topic's share is its window count over the window's
 * traces; a topic born after the first build is measured as any other. A topic that holds no trace in the final
 * assignment is retired the same night. Otherwise {@code lowNights} goes up by one on a night with a share below
 * {@value #RETIRE_SHARE} and back to 0 on any other night; at {@value #RETIRE_NIGHTS} the topic is retired. No cap
 * rule is needed: a birth on a full list replaces a topic, so the live list never grows past the maximum.
 */
@UtilityClass
public class Lifecycle {

    static final double RETIRE_SHARE = 0.005;
    static final int RETIRE_NIGHTS = 3;

    /**
     * Retirements: a topic with no trace tonight, then the low-share streak.
     *
     * @param labels per trace, a topic index or {@link PrototypeAssigner#OTHER}
     */
    public static void retire(@NonNull NightState state, @NonNull NightWindow window, @NonNull int[] labels) {
        int n = window.size();
        var counts = counts(state, labels);
        for (int t : state.live()) {
            if (counts[t] == 0) {
                state.retire(t, RetiredReason.LOW_SHARE);
                continue;
            }
            var topic = state.topic(t);
            double share = (double) counts[t] / n;
            topic.setLowNights(share < RETIRE_SHARE ? topic.getLowNights() + 1 : 0);
            if (topic.getLowNights() >= RETIRE_NIGHTS) {
                state.retire(t, RetiredReason.LOW_SHARE);
            }
        }
    }

    /** Each live topic's size: its count in the labels after the retire reassignment. */
    public static void sizes(@NonNull NightState state, @NonNull int[] labels) {
        var counts = counts(state, labels);
        for (int t : state.live()) {
            state.topic(t).setSize(counts[t]);
        }
    }

    static int[] counts(NightState state, int[] labels) {
        var counts = new int[state.getTopics().size()];
        for (int label : labels) {
            if (label >= 0) {
                counts[label]++;
            }
        }
        return counts;
    }
}
