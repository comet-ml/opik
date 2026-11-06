package com.comet.opik.domain.topics;

import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.Map;
import java.util.UUID;

/**
 * The stability of a nightly update (stage 2b D6), as the stand's {@code churn}: over the window traces labelled on both
 * the last saved night and tonight, {@code churnAny} is the share whose label changed, Other included; {@code churn} is
 * the same over the traces in a topic on both nights, 0 when there is none.
 */
@UtilityClass
public class TopicChurn {

    public record Churn(double churn, double churnAny) {
    }

    /**
     * @param previous last night's label per trace, {@link TraceTopic#OTHER} for Other
     * @param traceIds tonight's window traces
     * @param current  tonight's label per window trace, {@link TraceTopic#OTHER} for Other
     * @return null when no trace was labelled on both nights
     */
    public static Churn of(@NonNull Map<UUID, UUID> previous, @NonNull UUID[] traceIds, @NonNull UUID[] current) {
        int common = 0;
        int changed = 0;
        int inTopics = 0;
        int movedTopic = 0;
        for (int i = 0; i < traceIds.length; i++) {
            var before = previous.get(traceIds[i]);
            if (before == null) {
                continue;
            }
            boolean moved = !before.equals(current[i]);
            common++;
            changed += moved ? 1 : 0;
            if (!before.equals(TraceTopic.OTHER) && !current[i].equals(TraceTopic.OTHER)) {
                inTopics++;
                movedTopic += moved ? 1 : 0;
            }
        }
        if (common == 0) {
            return null;
        }
        return new Churn(inTopics == 0 ? 0 : (double) movedTopic / inTopics, (double) changed / common);
    }
}
