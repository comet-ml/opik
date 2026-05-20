package com.comet.opik.domain.evaluators;

import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;

public class EligibleWorkspaceRowMapper implements RowMapper<AutomationRuleMigrationDAO.EligibleWorkspace> {
    @Override
    public AutomationRuleMigrationDAO.EligibleWorkspace map(ResultSet rs, StatementContext ctx)
            throws SQLException {
        return new AutomationRuleMigrationDAO.EligibleWorkspace(
                rs.getString("workspace_id"),
                rs.getLong("multi_project_rule_count"));
    }
}
