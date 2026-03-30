package com.comet.opik.domain.retention;

import com.comet.opik.api.retention.RetentionLevel;
import com.comet.opik.api.retention.RetentionRule;
import com.comet.opik.infrastructure.db.RetentionLevelMapper;
import com.comet.opik.infrastructure.db.RetentionPeriodMapper;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(RetentionPeriodMapper.class)
@RegisterArgumentFactory(RetentionLevelMapper.class)
@RegisterColumnMapper(RetentionPeriodMapper.class)
@RegisterColumnMapper(RetentionLevelMapper.class)
@RegisterConstructorMapper(RetentionRule.class)
interface RetentionRuleDAO {

    @SqlUpdate("INSERT INTO retention_rules" +
            " (id, workspace_id, project_id, level, retention, apply_to_past, enabled, created_by, last_updated_by," +
            " catch_up_velocity, catch_up_cursor, catch_up_done)" +
            " VALUES" +
            " (:rule.id, :workspaceId, :rule.projectId, :rule.level, :rule.retention, :rule.applyToPast, :rule.enabled,"
            +
            " :rule.createdBy, :rule.lastUpdatedBy, :rule.catchUpVelocity, :rule.catchUpCursor, :rule.catchUpDone)")
    void save(@Bind("workspaceId") String workspaceId, @BindMethods("rule") RetentionRule rule);

    @SqlQuery("SELECT * FROM retention_rules WHERE id = :id AND workspace_id = :workspaceId")
    Optional<RetentionRule> findById(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT * FROM retention_rules" +
            " WHERE workspace_id = :workspaceId" +
            " <if(includeInactive)> <else> AND enabled = true <endif>" +
            " ORDER BY created_at DESC" +
            " LIMIT :limit OFFSET :offset")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<RetentionRule> find(@Bind("workspaceId") String workspaceId,
            @Define("includeInactive") @Bind("includeInactive") boolean includeInactive,
            @Bind("limit") int limit,
            @Bind("offset") int offset);

    @SqlQuery("SELECT COUNT(*) FROM retention_rules" +
            " WHERE workspace_id = :workspaceId" +
            " <if(includeInactive)> <else> AND enabled = true <endif>")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long count(@Bind("workspaceId") String workspaceId,
            @Define("includeInactive") @Bind("includeInactive") boolean includeInactive);

    @SqlUpdate("UPDATE retention_rules SET enabled = false, last_updated_by = :userName" +
            " WHERE id = :id AND workspace_id = :workspaceId AND enabled = true")
    int deactivate(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId,
            @Bind("userName") String userName);

    @SqlUpdate("UPDATE retention_rules SET enabled = false, last_updated_by = :userName" +
            " WHERE workspace_id = :workspaceId" +
            " AND COALESCE(project_id, '') = COALESCE(:projectId, '')" +
            " AND level = :level" +
            " AND enabled = true")
    int deactivateByScope(@Bind("workspaceId") String workspaceId,
            @Bind("projectId") UUID projectId,
            @Bind("level") RetentionLevel level,
            @Bind("userName") String userName);

    @SqlQuery("SELECT * FROM retention_rules" +
            " WHERE enabled = true AND retention != 'unlimited' AND project_id IS NULL" +
            " AND workspace_id >= :rangeStart AND workspace_id \\< :rangeEnd")
    @UseStringTemplateEngine
    List<RetentionRule> findActiveWorkspaceRulesInRange(@Bind("rangeStart") String rangeStart,
            @Bind("rangeEnd") String rangeEnd);

    // -- Catch-up queries --

    /**
     * Find small workspaces needing catch-up (velocity below threshold), ordered by cursor (most outdated first).
     * Note: catch_up_cursor is NULL only when catch_up_done=true, so the catch_up_done=false filter
     * guarantees non-null cursors in results. The service also filters nulls defensively.
     */
    @SqlQuery("SELECT * FROM retention_rules" +
            " WHERE catch_up_done = false AND enabled = true AND apply_to_past = true" +
            " AND catch_up_velocity IS NOT NULL AND catch_up_velocity < :smallThreshold" +
            " ORDER BY catch_up_cursor ASC" +
            " LIMIT :limit")
    List<RetentionRule> findSmallCatchUpRules(@Bind("smallThreshold") long smallThreshold,
            @Bind("limit") int limit);

    /** Find medium workspaces needing catch-up (velocity between thresholds), ordered by cursor. */
    @SqlQuery("SELECT * FROM retention_rules" +
            " WHERE catch_up_done = false AND enabled = true AND apply_to_past = true" +
            " AND catch_up_velocity IS NOT NULL" +
            " AND catch_up_velocity >= :smallThreshold AND catch_up_velocity < :largeThreshold" +
            " ORDER BY catch_up_cursor ASC" +
            " LIMIT :limit")
    List<RetentionRule> findMediumCatchUpRules(@Bind("smallThreshold") long smallThreshold,
            @Bind("largeThreshold") long largeThreshold,
            @Bind("limit") int limit);

    /** Find the single most outdated large workspace needing catch-up (velocity at or above threshold). */
    @SqlQuery("SELECT * FROM retention_rules" +
            " WHERE catch_up_done = false AND enabled = true AND apply_to_past = true" +
            " AND catch_up_velocity IS NOT NULL AND catch_up_velocity >= :largeThreshold" +
            " ORDER BY catch_up_cursor ASC" +
            " LIMIT 1")
    Optional<RetentionRule> findLargeCatchUpRule(@Bind("largeThreshold") long largeThreshold);

    /** Find rules pending velocity estimation (newly created with applyToPast=true, not yet estimated). FIFO order prevents a consistently failing rule from starving others. */
    @SqlQuery("""
            SELECT * FROM retention_rules
            WHERE catch_up_done = false AND enabled = true AND apply_to_past = true
            AND catch_up_velocity IS NULL
            ORDER BY created_at ASC
            LIMIT :limit
            """)
    List<RetentionRule> findUnestimatedCatchUpRules(@Bind("limit") int limit);

    /** Set velocity and cursor after estimation. */
    @SqlUpdate("UPDATE retention_rules SET catch_up_velocity = :velocity, catch_up_cursor = :cursor WHERE id = :id")
    void updateVelocityAndCursor(@Bind("id") UUID id, @Bind("velocity") long velocity, @Bind("cursor") UUID cursor);

    /** Advance the catch-up cursor for a rule. */
    @SqlUpdate("UPDATE retention_rules SET catch_up_cursor = :cursor WHERE id = :id")
    void updateCatchUpCursor(@Bind("id") UUID id, @Bind("cursor") UUID cursor);

    /** Mark catch-up as done for a rule. */
    @SqlUpdate("UPDATE retention_rules SET catch_up_done = true, catch_up_cursor = NULL WHERE id = :id")
    void markCatchUpDone(@Bind("id") UUID id);

    /** Batch-mark catch-up as done for multiple rules. */
    @SqlUpdate("UPDATE retention_rules SET catch_up_done = true, catch_up_cursor = NULL" +
            " WHERE id IN (<ids>)")
    @UseStringTemplateEngine
    void markCatchUpDoneBatch(@BindList("ids") List<UUID> ids);
}
