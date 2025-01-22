package com.comet.opik.api.events;

import com.comet.opik.api.Trace;

import java.util.UUID;

public interface ScoringMessage {
    Trace trace();

    UUID ruleId();

    String ruleName();

    String workspaceId();

    String userName();
}
