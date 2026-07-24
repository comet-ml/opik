package com.comet.opik.domain;

import com.comet.opik.api.AgentInsightsIssue;
import com.comet.opik.api.AgentInsightsIssueDetail;
import com.comet.opik.api.AgentInsightsIssueSeverity;
import com.comet.opik.api.AgentInsightsIssueStatus;
import com.comet.opik.api.AgentInsightsIssueWithDetails;
import com.comet.opik.api.AgentInsightsReport;
import com.comet.opik.infrastructure.db.AgentInsightsIssueSeverityColumnMapper;
import com.comet.opik.infrastructure.db.AgentInsightsIssueStatusColumnMapper;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import com.comet.opik.utils.JsonUtils;
import org.apache.commons.lang3.StringUtils;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RegisterConstructorMapper(AgentInsightsIssue.class)
@RegisterRowMapper(AgentInsightsIssueDAO.IssueWithDetailsRowMapper.class)
@RegisterRowMapper(AgentInsightsIssueDAO.IssueDetailRowMapper.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(AgentInsightsIssueStatusColumnMapper.class)
@RegisterColumnMapper(AgentInsightsIssueStatusColumnMapper.class)
@RegisterArgumentFactory(AgentInsightsIssueSeverityColumnMapper.class)
@RegisterColumnMapper(AgentInsightsIssueSeverityColumnMapper.class)
interface AgentInsightsIssueDAO {

    @SqlBatch("""
            INSERT INTO agent_insights_issues
                (id, workspace_id, project_id, name, description, cause, suggested_fix, traces_query, severity, created_by, last_updated_by)
            VALUES (:id, :workspace_id, :project_id, :bean.name, :bean.description, :bean.cause, :bean.suggestedFix,
                    :bean.tracesQuery, :bean.severity, :user_name, :user_name)
            ON DUPLICATE KEY UPDATE
                name = :bean.name,
                description = :bean.description,
                cause = :bean.cause,
                suggested_fix = :bean.suggestedFix,
                traces_query = :bean.tracesQuery,
                severity = :bean.severity,
                last_updated_by = :user_name
            """)
    void upsertIssues(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("user_name") String userName,
            @Bind("id") List<UUID> ids,
            @BindMethods("bean") List<AgentInsightsReport.ReportedIssue> issues);

    @SqlBatch("""
            INSERT INTO agent_insights_issues_details
                (id, issue_id, workspace_id, project_id, `count`, total_count, users_impacted, total_users,
                 metadata, report_day, created_by, last_updated_by)
            VALUES (:id, :issue_id, :workspace_id, :project_id, :bean.count, :bean.totalCount, :bean.usersImpacted,
                    :bean.totalUsers, :metadata, :report_day, :user_name, :user_name)
            ON DUPLICATE KEY UPDATE
                `count` = :bean.count,
                total_count = :bean.totalCount,
                users_impacted = :bean.usersImpacted,
                total_users = :bean.totalUsers,
                metadata = :metadata,
                last_updated_by = :user_name
            """)
    void upsertDetails(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("report_day") LocalDate reportDay,
            @Bind("user_name") String userName,
            @Bind("id") List<UUID> ids,
            @Bind("issue_id") List<UUID> issueIds,
            @BindMethods("bean") List<AgentInsightsReport.ReportedIssue> issues,
            @Bind("metadata") List<String> metadata);

    // Aggregate the details per issue in a subquery (agg), then join once more to the row for the most recent
    // report day (latest) for latest_count. The prose (description/cause) narrates the latest run, so the UI
    // needs latest_count consistent with it, alongside total_occurrences for overall scale. The details table is
    // unique per (report_day, issue), so the latest join yields exactly one row per issue. Prefer this to
    // window functions so the aggregation can use the details index rather than scanning/partitioning every row.
    @SqlQuery("""
            SELECT i.id, i.name, i.description, i.cause, i.suggested_fix, i.status, i.severity, i.traces_query,
                   agg.total_occurrences, latest.`count` AS latest_count, agg.total,
                   agg.users_impacted, agg.total_users, agg.first_seen, agg.last_seen, agg.days_reported,
                   i.created_by, i.created_at, i.last_updated_by, i.last_updated_at
            FROM agent_insights_issues i
            JOIN (
                SELECT workspace_id, project_id, issue_id,
                       SUM(`count`) AS total_occurrences,
                       SUM(total_count) AS total,
                       SUM(users_impacted) AS users_impacted,
                       SUM(total_users) AS total_users,
                       MIN(report_day) AS first_seen,
                       MAX(report_day) AS last_seen,
                       COUNT(DISTINCT report_day) AS days_reported
                FROM agent_insights_issues_details
                WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND report_day BETWEEN :from_date AND :to_date
                GROUP BY workspace_id, project_id, issue_id
            ) agg
                ON agg.workspace_id = i.workspace_id
                AND agg.project_id = i.project_id
                AND agg.issue_id = i.id
            JOIN agent_insights_issues_details latest
                ON latest.workspace_id = agg.workspace_id
                AND latest.project_id = agg.project_id
                AND latest.issue_id = agg.issue_id
                AND latest.report_day = agg.last_seen
            WHERE i.workspace_id = :workspace_id
                AND i.project_id = :project_id
                <if(status)> AND i.status = :status <endif>
                <if(severity)> AND i.severity = :severity <endif>
            ORDER BY <if(sort_fields)> <sort_fields>, <endif> agg.last_seen DESC, agg.total_occurrences DESC, i.id DESC
            LIMIT :limit OFFSET :offset
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<AgentInsightsIssue> findIssues(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("from_date") LocalDate fromDate,
            @Bind("to_date") LocalDate toDate,
            @Define("status") @Bind("status") AgentInsightsIssueStatus status,
            @Define("severity") @Bind("severity") AgentInsightsIssueSeverity severity,
            @Define("sort_fields") String sortFields,
            @Bind("limit") int limit,
            @Bind("offset") int offset);

    @SqlQuery("""
            SELECT COUNT(DISTINCT i.id)
            FROM agent_insights_issues i
            JOIN agent_insights_issues_details d
                ON d.workspace_id = i.workspace_id
                AND d.project_id = i.project_id
                AND d.issue_id = i.id
            WHERE i.workspace_id = :workspace_id
                AND i.project_id = :project_id
                AND d.report_day BETWEEN :from_date AND :to_date
                <if(status)> AND i.status = :status <endif>
                <if(severity)> AND i.severity = :severity <endif>
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long countIssues(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("from_date") LocalDate fromDate,
            @Bind("to_date") LocalDate toDate,
            @Define("status") @Bind("status") AgentInsightsIssueStatus status,
            @Define("severity") @Bind("severity") AgentInsightsIssueSeverity severity);

    @SqlQuery("""
            SELECT id, name, description, cause, suggested_fix, status, severity, traces_query, created_by, created_at, last_updated_by, last_updated_at
            FROM agent_insights_issues
            WHERE workspace_id = :workspace_id AND project_id = :project_id AND id = :id
            """)
    AgentInsightsIssueWithDetails findIssueById(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("id") UUID id);

    @SqlQuery("""
            SELECT report_day, `count`, total_count, users_impacted, total_users, metadata
            FROM agent_insights_issues_details
            WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND issue_id = :issue_id
                AND report_day BETWEEN :from_date AND :to_date
            ORDER BY report_day ASC
            """)
    List<AgentInsightsIssueDetail> findDetails(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("issue_id") UUID issueId,
            @Bind("from_date") LocalDate fromDate,
            @Bind("to_date") LocalDate toDate);

    @SqlUpdate("""
            UPDATE agent_insights_issues
            SET status = :status, last_updated_by = :user_name
            WHERE workspace_id = :workspace_id AND project_id = :project_id AND id = :id
            """)
    int updateStatus(
            @Bind("workspace_id") String workspaceId,
            @Bind("project_id") UUID projectId,
            @Bind("id") UUID id,
            @Bind("status") AgentInsightsIssueStatus status,
            @Bind("user_name") String userName);

    class IssueWithDetailsRowMapper implements RowMapper<AgentInsightsIssueWithDetails> {

        @Override
        public AgentInsightsIssueWithDetails map(ResultSet rs, StatementContext ctx) throws SQLException {
            String severityStr = rs.getString("severity");
            return AgentInsightsIssueWithDetails.builder()
                    .id(UUID.fromString(rs.getString("id")))
                    .name(rs.getString("name"))
                    .description(rs.getString("description"))
                    .cause(rs.getString("cause"))
                    .suggestedFix(rs.getString("suggested_fix"))
                    .status(AgentInsightsIssueStatus.fromString(rs.getString("status")))
                    .severity(severityStr != null ? AgentInsightsIssueSeverity.fromString(severityStr) : null)
                    .tracesQuery(rs.getString("traces_query"))
                    .createdBy(rs.getString("created_by"))
                    .createdAt(rs.getTimestamp("created_at").toInstant())
                    .lastUpdatedBy(rs.getString("last_updated_by"))
                    .lastUpdatedAt(rs.getTimestamp("last_updated_at").toInstant())
                    .build();
        }
    }

    class IssueDetailRowMapper implements RowMapper<AgentInsightsIssueDetail> {

        @Override
        public AgentInsightsIssueDetail map(ResultSet rs, StatementContext ctx) throws SQLException {
            String metadata = rs.getString("metadata");
            return AgentInsightsIssueDetail.builder()
                    .reportDay(rs.getObject("report_day", LocalDate.class))
                    .count(rs.getLong("count"))
                    .totalCount(rs.getLong("total_count"))
                    .usersImpacted(rs.getLong("users_impacted"))
                    .totalUsers(rs.getLong("total_users"))
                    .metadata(StringUtils.isBlank(metadata) ? null : JsonUtils.getJsonNodeFromString(metadata))
                    .build();
        }
    }
}
