package com.comet.opik.domain;

/**
 * Which read-only ClickHouse account runs a free-form SQL request.
 *
 * <p>The two differ in their row policies, not just their grants: {@link #STANDARD} binds every one of its three
 * tables to workspace <em>and</em> project, while {@link #EXTENDED} reads five more, keeps the tables carrying
 * {@code project_id} in their primary key project-bound, and lets the caller choose whether that bound applies at
 * all. Its {@code experiments}, {@code experiment_items} and {@code dataset_items} are workspace-bound only.
 *
 * <p>Transitional by design. When the remaining {@link #STANDARD} caller moves across, this enum goes away.
 */
public enum FreeFormSqlAccount {
    STANDARD,
    EXTENDED
}
