package com.comet.opik.infrastructure.db;

import com.comet.opik.api.PromptVersionLink;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public class PromptVersionLinkRowMapper implements RowMapper<PromptVersionLink> {

    @Override
    public PromptVersionLink map(ResultSet rs, StatementContext ctx) throws SQLException {
        return PromptVersionLink.builder()
                .promptVersionId(rs.getObject("prompt_version_id", UUID.class))
                .commit(rs.getString("commit"))
                .promptId(rs.getObject("id", UUID.class))
                .promptName(rs.getString("name"))
                .build();
    }
}
