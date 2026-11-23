package com.comet.opik.domain.topics;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * How far Topics goes for a workspace listed in {@code topics.enabledWorkspaces}, in declaration order. A workspace at
 * a stage runs that stage and every earlier one; see {@link TopicWorkspaceService#reaches}.
 */
public enum TopicsStage {
    VECTORS,
    BUILD,
    ARRIVAL,
    NIGHTLY,
    UI;

    /** The stage of a listed workspace id without a {@code :stage} suffix: every stage. */
    public static final TopicsStage ALL = UI;

    /** The name used after the colon in {@code topics.enabledWorkspaces}. */
    public String configName() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The stage whose {@link #configName()} is exactly {@code configName}, else empty. */
    public static Optional<TopicsStage> fromConfigName(String configName) {
        return Arrays.stream(values())
                .filter(stage -> stage.configName().equals(configName))
                .findFirst();
    }

    /** True when this stage is {@code stage} or a later one. */
    public boolean includes(TopicsStage stage) {
        return compareTo(stage) >= 0;
    }
}
