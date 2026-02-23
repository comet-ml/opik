package com.comet.opik.infrastructure.db;

import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptVersionLink;
import com.comet.opik.api.TemplateStructure;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;

public class PromptVersionLinkRowMapper implements RowMapper<PromptVersionLink> {

    private final SetFlatArgumentFactory tagsMapper = new SetFlatArgumentFactory();

    @Override
    public PromptVersionLink map(ResultSet rs, StatementContext ctx) throws SQLException {
        Set<String> tags = tagsMapper.map(rs, "tags", ctx);

        Prompt prompt = Prompt.builder()
                .id(rs.getObject("id", UUID.class))
                .name(rs.getString("name"))
                .description(rs.getString("description"))
                .templateStructure(TemplateStructure.fromString(rs.getString("template_structure")))
                .tags(tags)
                .createdAt(rs.getTimestamp("created_at").toInstant())
                .createdBy(rs.getString("created_by"))
                .lastUpdatedAt(rs.getTimestamp("last_updated_at").toInstant())
                .lastUpdatedBy(rs.getString("last_updated_by"))
                .build();

        return PromptVersionLink.builder()
                .promptVersionId(rs.getObject("prompt_version_id", UUID.class))
                .commit(rs.getString("commit"))
                .prompt(prompt)
                .build();
    }
}
