package com.comet.opik.domain;

/**
 * Which consumer a free-form SQL request belongs to, and with it which read-only ClickHouse account runs it.
 *
 * <p>The two accounts differ in their row policies, not just their grants: Agent Insights binds every one of its
 * three tables to workspace <em>and</em> project, while Custom Charts keeps only {@code traces} and {@code spans}
 * project-bound and reads the evaluation tables workspace-wide. Relaxing the shared account in place would widen
 * the existing feature's reach, so the split is deliberate rather than incidental.
 */
public enum AnalyticsConsumer {
    AGENT_INSIGHTS,
    CUSTOM_CHARTS
}
