package com.comet.opik.domain;

import com.comet.opik.api.Tag;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public class TagMapper implements RowMapper<Tag> {

    @Override
    public Tag map(ResultSet rs, StatementContext ctx) throws SQLException {
        return new Tag(
                UUID.fromString(rs.getString("id")),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("workspace_id"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}