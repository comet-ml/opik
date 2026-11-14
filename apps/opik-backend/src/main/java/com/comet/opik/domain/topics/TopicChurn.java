package com.comet.opik.domain.topics;

import com.google.common.base.Preconditions;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

/**
 * The stability of a nightly update, over every window trace: its label by the list saved before tonight against
 * tonight's. {@code churnAny} is the share whose label changed, Other included; {@code churn} is the same over the
 * traces in a topic both before and after, 0 when there is none. Labels are topic indices of the night, so a trace of a
 * topic retired tonight, or now in a topic born tonight, has changed.
 */
@UtilityClass
public class TopicChurn {

    public record Churn(double churn, double churnAny) {
    }

    /**
     * @param before step a's label per window trace, a topic index or {@link PrototypeAssigner#OTHER}
     * @param after  tonight's final label per window trace, the same way
     */
    public static Churn of(@NonNull int[] before, @NonNull int[] after) {
        Preconditions.checkArgument(before.length == after.length, "one label per trace on both sides");
        int changed = 0;
        int inTopics = 0;
        int movedTopic = 0;
        for (int i = 0; i < before.length; i++) {
            boolean moved = before[i] != after[i];
            changed += moved ? 1 : 0;
            if (before[i] != PrototypeAssigner.OTHER && after[i] != PrototypeAssigner.OTHER) {
                inTopics++;
                movedTopic += moved ? 1 : 0;
            }
        }
        return new Churn(inTopics == 0 ? 0 : (double) movedTopic / inTopics,
                before.length == 0 ? 0 : (double) changed / before.length);
    }
}
