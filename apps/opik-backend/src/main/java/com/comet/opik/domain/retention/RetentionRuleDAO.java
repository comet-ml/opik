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
            " (id, workspace_id, project_id, level, retention, apply_to_past, enabled, created_by, last_updated_by)" +
            " VALUES" +
            " (:rule.id, :workspaceId, :rule.projectId, :rule.level, :rule.retention, :rule.applyToPast, :rule.enabled, :rule.createdBy, :rule.lastUpdatedBy)")
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
}
