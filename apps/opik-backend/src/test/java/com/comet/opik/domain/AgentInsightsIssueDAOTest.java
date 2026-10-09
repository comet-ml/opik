package com.comet.opik.domain;

import com.comet.opik.api.AgentInsightsIssueSeverity;
import com.comet.opik.api.AgentInsightsReport;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import org.jdbi.v3.core.Jdbi;
import org.jdbi.v3.sqlobject.SqlObjectPlugin;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.testcontainers.mysql.MySQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the scope guards in {@link AgentInsightsIssueDAO#upsertIssues} directly. The service filters foreign ids
 * before it gets here, so only a DAO-level call shows the guards themselves hold.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Agent Insights Issue DAO")
class AgentInsightsIssueDAOTest {

    private final MySQLContainer mysql = MySQLContainerUtils.newMySQLContainer();
    private Jdbi jdbi;

    @BeforeAll
    void setUp() {
        mysql.start();
        MigrationUtils.runMysqlDbMigration(mysql);
        jdbi = Jdbi.create(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                .installPlugin(new SqlObjectPlugin());
    }

    private static AgentInsightsReport.ReportedIssue issue(String name) {
        return AgentInsightsReport.ReportedIssue.builder()
                .name(name)
                .description("Description of " + name)
                .cause("Cause of " + name)
                .suggestedFix("Fix for " + name)
                .tracesQuery("SELECT 1")
                .severity(AgentInsightsIssueSeverity.MEDIUM)
                .build();
    }

    @Test
    @DisplayName("An upsert from another workspace or project leaves the existing issue untouched")
    void upsertIssues__idOwnedByAnotherScope__leavesRowUntouched() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        var issueId = UUID.randomUUID();
        var original = issue("original");

        jdbi.useHandle(handle -> {
            var dao = handle.attach(AgentInsightsIssueDAO.class);
            dao.upsertIssues(workspaceId, projectId, "owner", List.of(issueId), List.of(original));
            var before = dao.findIssueById(workspaceId, projectId, issueId);

            dao.upsertIssues(workspaceId, UUID.randomUUID(), "intruder", List.of(issueId),
                    List.of(issue("from another project")));
            dao.upsertIssues(UUID.randomUUID().toString(), projectId, "intruder", List.of(issueId),
                    List.of(issue("from another workspace")));

            assertThat(dao.findIssueById(workspaceId, projectId, issueId))
                    .usingRecursiveComparison()
                    .isEqualTo(before);
            assertThat(before.name()).isEqualTo("original");
        });
    }

    @Test
    @DisplayName("An upsert from the owning scope updates the issue")
    void upsertIssues__sameScope__updatesRow() {
        var workspaceId = UUID.randomUUID().toString();
        var projectId = UUID.randomUUID();
        var issueId = UUID.randomUUID();

        jdbi.useHandle(handle -> {
            var dao = handle.attach(AgentInsightsIssueDAO.class);
            dao.upsertIssues(workspaceId, projectId, "owner", List.of(issueId), List.of(issue("original")));
            dao.upsertIssues(workspaceId, projectId, "owner", List.of(issueId), List.of(issue("renamed")));

            var stored = dao.findIssueById(workspaceId, projectId, issueId);
            assertThat(stored.name()).isEqualTo("renamed");
            assertThat(stored.description()).isEqualTo("Description of renamed");
        });
    }
}
