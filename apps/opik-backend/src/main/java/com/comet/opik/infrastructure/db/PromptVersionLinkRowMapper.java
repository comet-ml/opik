package com.comet.opik.infrastructure.db;

import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptVersionLink;
import com.comet.opik.api.TemplateStructure;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class PromptVersionLinkRowMapper implements RowMapper<PromptVersionLink> {

    private static final TypeReference<Set<String>> SET_TYPE_REFERENCE = new TypeReference<>() {
    };

    @Override
    public PromptVersionLink map(ResultSet rs, StatementContext ctx) throws SQLException {
        var prompt = Prompt.builder()
                .id(rs.getObject("id", UUID.class))
                .name(rs.getString("name"))
                .description(rs.getString("description"))
                .templateStructure(TemplateStructure.fromString(rs.getString("template_structure")))
                .tags(
                        Optional.ofNullable(rs.getString("tags"))
                                .map(v -> JsonUtils.readValue(v, SET_TYPE_REFERENCE))
                                .orElse(null))
                .createdAt(
                        Optional.ofNullable(rs.getTimestamp("created_at"))
                                .map(Timestamp::toInstant)
                                .orElse(null))
                .createdBy(rs.getString("created_by"))
                .lastUpdatedAt(
                        Optional.ofNullable(rs.getTimestamp("last_updated_at"))
                                .map(Timestamp::toInstant)
                                .orElse(null))
                .lastUpdatedBy(rs.getString("last_updated_by"))
                .build();

        return PromptVersionLink.builder()
                .promptVersionId(rs.getObject("prompt_version_id", UUID.class))
                .commit(rs.getString("commit"))
                .prompt(prompt)
                .build();
    }
}
