package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicsRunException.Code;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What must hold after a nightly update before anything is written (stage 2b D4, extending 2a D5). A violation fails the
 * run with {@code invariant}.
 */
@UtilityClass
public class NightInvariants {

    static final double UNIT_TOLERANCE = 1e-3;

    /**
     * @param names   the name of every topic read at the start, by id
     * @param retired the ids of the project's topics retired before tonight
     * @param labels  tonight's labels, a topic index or {@link PrototypeAssigner#OTHER} per window trace
     */
    public static void check(@NonNull NightState state, @NonNull Map<UUID, String> names,
            @NonNull List<UUID> retired, @NonNull int[] labels, int windowSize, int maxTopics, int dimensions) {
        if (state.liveCount() > maxTopics) {
            throw invariant("'%s' live topics, at most '%s'".formatted(state.liveCount(), maxTopics));
        }
        var ids = new HashSet<UUID>();
        for (var topic : state.getTopics()) {
            if (!ids.add(topic.getId())) {
                throw invariant("topic id '%s' twice".formatted(topic.getId()));
            }
            if (topic.isLive() && retired.contains(topic.getId())) {
                throw invariant("retired topic '%s' is live".formatted(topic.getId()));
            }
            var name = names.get(topic.getId());
            if (name == null) {
                if (!topic.getBorn().equals(state.getNight())) {
                    throw invariant("new topic '%s' born '%s', not tonight".formatted(topic.getName(),
                            topic.getBorn()));
                }
                if (StringUtils.isBlank(topic.getName()) || AuthorApplier.isCatchAll(topic.getName())) {
                    throw invariant("topic name '%s' is blank or a catch-all".formatted(topic.getName()));
                }
            } else if (!name.equals(topic.getName())) {
                throw invariant("topic '%s' renamed '%s'".formatted(name, topic.getName()));
            }
            if (topic.isLive()) {
                topic.getPrototypes().forEach(prototype -> unit(prototype, dimensions));
            }
        }
        state.getOtherPrototypes().forEach(prototype -> unit(prototype, dimensions));
        if (labels.length != windowSize) {
            throw invariant("'%s' labels for '%s' traces".formatted(labels.length, windowSize));
        }
        for (int label : labels) {
            if (label != PrototypeAssigner.OTHER
                    && (label < 0 || label >= state.getTopics().size() || !state.topic(label).isLive())) {
                throw invariant("a trace labelled '%s', not a live topic".formatted(label));
            }
        }
    }

    private static void unit(float[] prototype, int dimensions) {
        double norm = 0;
        for (float value : prototype) {
            norm += (double) value * value;
        }
        if (prototype.length != dimensions || Math.abs(Math.sqrt(norm) - 1) > UNIT_TOLERANCE) {
            throw invariant("a prototype has '%s' values and norm '%s'".formatted(prototype.length,
                    Math.sqrt(norm)));
        }
    }

    private static TopicsRunException invariant(String message) {
        return new TopicsRunException(Code.INVARIANT, message);
    }
}
