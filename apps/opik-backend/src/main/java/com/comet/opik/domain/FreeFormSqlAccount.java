package com.comet.opik.domain;

/**
 * Which read-only ClickHouse account runs a free-form SQL request.
 *
 * <p>The two differ in their row policies, not just their grants: {@link #STANDARD} binds every one of its three
 * tables to workspace <em>and</em> project, while {@link #EXTENDED} reads five more, keeps only {@code traces} and
 * {@code spans} project-bound, and lets the caller choose whether that bound applies at all.
 *
 * <p>Transitional by design. When the remaining {@link #STANDARD} caller moves across, this enum goes away.
 */
public enum FreeFormSqlAccount {
    STANDARD,
    EXTENDED
}
