package com.comet.opik.domain;

/**
 * Which consumer a free-form SQL request belongs to, and with it which read-only ClickHouse account runs it.
 *
 * <p>The two accounts differ in their row policies, not just their grants: AGENT_INSIGHTS binds every one of its
 * three tables to workspace <em>and</em> project, while CUSTOM_DASHBOARD_CHARTS keeps only {@code traces} and {@code spans}
 * project-bound and reads the evaluation tables workspace-wide.
 */
public enum AnalyticsConsumer {
    AGENT_INSIGHTS,
    CUSTOM_DASHBOARD_CHARTS
}
