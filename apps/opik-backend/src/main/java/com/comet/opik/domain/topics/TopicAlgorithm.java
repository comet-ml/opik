package com.comet.opik.domain.topics;

import com.google.inject.ImplementedBy;
import jakarta.annotation.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * The topic algorithm: how a project's window of vectors becomes its topic list, and the rule that labels a trace by a
 * saved list. The rebuild service does everything around a build: it reads the window, makes the LLM calls the
 * algorithm asks for, checks the prototypes, saves and counts. The labelling pass labels the window's other traces
 * with {@link #labeller}. To try another algorithm, implement this interface and bind it in place of
 * {@link KMeansTopicAlgorithm}.
 *
 * <p>The limit of this seam: an algorithm leaves its list in the saved format the service owns. That is the topics
 * (name, definition; the service sets their lifecycle columns), the prototype vectors of each topic, and known-Other
 * prototypes. A trace is labelled by the nearest saved prototype. An algorithm that cannot express its result as
 * prototypes does not fit this seam.
 *
 * <p>The labels a build returns should be the ones its {@link #labeller} gives with the prototypes it returns:
 * otherwise a trace the build read and a trace the labelling pass labels get their topic by two different rules.
 *
 * <p>Values every algorithm shares live here; the vector size is {@link TopicEmbedding#DIMENSIONS}. Values one
 * algorithm needs stay with it.
 */
@ImplementedBy(KMeansTopicAlgorithm.class)
public interface TopicAlgorithm {

    /** The label of a trace in no topic. */
    int OTHER = -1;

    /** Builds the first list of a project that has none. */
    FirstBuild firstBuild(TopicWindow window, int maxTopics, TopicRunContext context);

    /**
     * The rule that labels vectors by a project's saved list. Empty when the list has no topic: then it labels nothing.
     *
     * @param topics the project's live topics in saved order, {@code (born, id)}: with each topic's vectors in their
     *               own order, this is the order that breaks a tie between two prototypes
     * @param otherVectors the known-Other prototypes of the saved list, or null when it has none
     */
    Optional<TopicLabeller> labeller(List<Topic> topics, @Nullable PrototypeVectors otherVectors);
}
