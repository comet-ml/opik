package com.comet.opik.domain.topics;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A discovery run up to the LLM call: {@link TopicAlgorithm#cluster} groups the window and writes the author request, the
 * run makes the call, and {@link #finish} turns the answer into the list. Pure: no I/O, no clock.
 */
public interface Clustering {

    /** The call that names the topics over the groups. */
    LlmRequest author();

    /** The algorithm's own stats so far; the run adds them to its logged stats. */
    ObjectNode stats();

    /** The list from the parsed author answer, labelling every window trace. */
    DiscoveredTopics finish(AuthorAnswer answer);
}
