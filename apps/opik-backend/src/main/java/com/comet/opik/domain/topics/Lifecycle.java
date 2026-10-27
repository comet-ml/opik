package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.NightState.RetiredReason;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Step l of the nightly update, after the final assignment, as the stand's lifecycle variant v7f:
 * <ul>
 * <li>share = the topic's window count over the window's traces; a topic born after the first build whose
 * {@code born - 7 days} is later than the window's start is measured from then on (newborn protection);</li>
 * <li>{@code lowNights} + 1 when the share is below {@value #RETIRE_SHARE}, else 0; {@value #RETIRE_NIGHTS} retires;</li>
 * <li>while more than {@code maxTopics} are live, the one with the smallest count is retired;</li>
 * <li>inactive (display): an active topic counts {@code lo7} nights with a 7-day share below {@value #RETIRE_SHARE}
 * and becomes inactive at {@value #RETIRE_NIGHTS}; an inactive one counts {@code hi7} nights at
 * {@value #ACTIVE_SHARE} or more and is active again at {@value #ACTIVE_NIGHTS}.</li>
 * </ul>
 * Ties go to the earlier topic in list order.
 */
@UtilityClass
public class Lifecycle {

    static final double RETIRE_SHARE = 0.005;
    static final int RETIRE_NIGHTS = 3;
    static final double ACTIVE_SHARE = 0.01;
    static final int ACTIVE_NIGHTS = 2;
    static final int NEWBORN_DAYS = 7;

    /**
     * Retirements by share and by the cap.
     *
     * @param labels per trace, a topic index or {@link PrototypeAssigner#OTHER}
     * @return the share each topic live at the start of this step was judged by, by topic index
     */
    public static Map<Integer, Double> retire(@NonNull NightState state, @NonNull NightWindow window,
            @NonNull int[] labels, int maxTopics) {
        int n = window.size();
        var counts = counts(state, labels);
        var shares = new LinkedHashMap<Integer, Double>();
        for (int t : state.live()) {
            var topic = state.topic(t);
            var since = topic.getBorn().minusDays(NEWBORN_DAYS).atStartOfDay(ZoneOffset.UTC).toInstant();
            double share;
            if (topic.getBorn().isAfter(state.getFirstBuild()) && window.from().isBefore(since)) {
                var newborn = window.since(since);
                int members = 0;
                int total = 0;
                for (int i = 0; i < n; i++) {
                    if (newborn[i]) {
                        total++;
                        members += labels[i] == t ? 1 : 0;
                    }
                }
                share = (double) members / Math.max(1, total);
            } else {
                share = (double) counts[t] / n;
            }
            shares.put(t, share);
            topic.setLowNights(share < RETIRE_SHARE ? topic.getLowNights() + 1 : 0);
            if (topic.getLowNights() >= RETIRE_NIGHTS) {
                state.retire(t, RetiredReason.LOW_SHARE);
            }
        }
        while (state.liveCount() > maxTopics) {
            Integer smallest = null;
            for (int t : state.live()) {
                if (smallest == null || counts[t] < counts[smallest]) {
                    smallest = t;
                }
            }
            state.retire(smallest, RetiredReason.CAP);
        }
        return shares;
    }

    /** The inactive switch and the sizes, on the labels after the retire reassignment. */
    public static void display(@NonNull NightState state, @NonNull NightWindow window, @NonNull int[] labels) {
        var recent = window.recent();
        int recentTotal = 0;
        for (boolean r : recent) {
            recentTotal += r ? 1 : 0;
        }
        int denominator = Math.max(1, recentTotal);
        var counts = counts(state, labels);
        var recentCounts = new int[state.getTopics().size()];
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] >= 0 && recent[i]) {
                recentCounts[labels[i]]++;
            }
        }
        for (int t : state.live()) {
            var topic = state.topic(t);
            topic.setSize(counts[t]);
            double s7 = (double) recentCounts[t] / denominator;
            if (topic.getInactiveSince() == null) {
                topic.setLo7(s7 < RETIRE_SHARE ? topic.getLo7() + 1 : 0);
                if (topic.getLo7() >= RETIRE_NIGHTS) {
                    topic.setInactiveSince(state.getNight());
                    topic.setHi7(0);
                }
            } else {
                topic.setHi7(s7 >= ACTIVE_SHARE ? topic.getHi7() + 1 : 0);
                if (topic.getHi7() >= ACTIVE_NIGHTS) {
                    topic.setInactiveSince(null);
                    topic.setLo7(0);
                }
            }
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
