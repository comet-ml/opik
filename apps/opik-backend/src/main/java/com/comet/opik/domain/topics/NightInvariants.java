package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicProject.RunError;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.HashMap;
import java.util.UUID;

/**
 * Checks the night's list before anything is written. Two live topics must not have one name (case and spaces
 * ignored). Every prototype of a live topic and of the known-Other memory must have {@code dimensions} values and unit
 * length, by the discovery run's check ({@link PrototypeVectors#isUnit}). A failed check fails the run with
 * {@code invariant}.
 */
@UtilityClass
class NightInvariants {

    static void check(@NonNull NightState state, int dimensions) {
        var liveKeys = new HashMap<String, UUID>();
        for (int t : state.live()) {
            var topic = state.topic(t);
            // Ids only: a name comes from trace text and must not reach error_message or the logs.
            var other = liveKeys.putIfAbsent(AuthorApplier.nameKey(topic.getName()), topic.getId());
            if (other != null) {
                throw invariant("live topics '%s' and '%s' have one name".formatted(other, topic.getId()));
            }
            topic.getPrototypes().forEach(prototype -> unit(prototype, dimensions));
        }
        state.getOtherPrototypes().forEach(prototype -> unit(prototype, dimensions));
    }

    private static void unit(float[] prototype, int dimensions) {
        if (!PrototypeVectors.isUnit(prototype, dimensions)) {
            throw invariant("a prototype has '%s' values and norm '%s'".formatted(prototype.length,
                    PrototypeVectors.norm(prototype)));
        }
    }

    private static TopicsRunException invariant(String message) {
        return new TopicsRunException(RunError.INVARIANT, message);
    }
}
