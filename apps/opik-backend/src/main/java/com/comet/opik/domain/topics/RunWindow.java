package com.comet.opik.domain.topics;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.TreeMap;

/** The window as the run reads it: per trace, in time order, its text and vector, plus its stats. */
record RunWindow(String[] texts, float[] vectors, List<WindowTrace> traces, TreeMap<Integer, Integer> versions,
        ObjectNode stats) {

    TopicWindow topicWindow() {
        return TopicWindow.builder().texts(texts).vectors(vectors).build();
    }
}
