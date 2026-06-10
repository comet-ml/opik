package com.comet.opik.infrastructure.db;

import com.comet.opik.api.PromptType;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.PromptVersionType;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.TemplateParseUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.jdbi.v3.core.mapper.ColumnMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public class PromptVersionColumnMapper implements ColumnMapper<PromptVersion> {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")
            .withZone(java.time.ZoneOffset.UTC);

    @Override
    public PromptVersion map(ResultSet r, int columnNumber, StatementContext ctx) throws SQLException {
        return Optional.ofNullable(r.getString(columnNumber))
                .map(JsonUtils::getJsonNodeFromString)
                .map(this::mapObject)
                .orElse(null);
    }

    private PromptVersion mapObject(JsonNode jsonNode) {
        String template = jsonNode.get("template").asText();
        PromptType type = PromptType.fromString(jsonNode.get("type").asText());
        PromptVersionType versionType = Optional.ofNullable(jsonNode.get("version_type"))
                .filter(node -> !node.isNull())
                .map(JsonNode::asText)
                .map(PromptVersionType::fromString)
                .orElse(PromptVersionType.PROMPT_VERSION);
        Set<String> environments = Optional.ofNullable(jsonNode.get("environments"))
                .filter(node -> !node.isNull() && node.isArray())
                .map(node -> {
                    Set<String> result = new HashSet<>();
                    node.forEach(e -> result.add(e.asText()));
                    return result.isEmpty() ? null : result;
                })
                .orElse(null);
        String versionNumber = Optional.ofNullable(jsonNode.get("version_number"))
                .filter(node -> !node.isNull())
                .map(JsonNode::asText)
                .orElse(null);

        return PromptVersion.builder()
                .id(UUID.fromString(jsonNode.get("id").asText()))
                .promptId(UUID.fromString(jsonNode.get("prompt_id").asText()))
                .commit(jsonNode.get("commit").asText())
                .versionNumber(versionNumber)
                .template(template)
                .metadata(jsonNode.get("metadata"))
                .changeDescription(jsonNode.get("change_description").asText())
                .type(type)
                .versionType(versionType)
                .environments(environments)
                .variables(TemplateParseUtils.extractVariables(template, type))
                .tags(jsonNode.has("tags") && jsonNode.get("tags") != null && !jsonNode.get("tags").isNull()
                        ? JsonUtils.readValue(jsonNode.get("tags").asText(), SetFlatArgumentFactory.TYPE_REFERENCE)
                        : null)
                .createdAt(Instant.from(FORMATTER.parse(jsonNode.get("created_at").asText())))
                .createdBy(jsonNode.get("created_by").asText())
                .build();
    }

    @Override
    public PromptVersion map(ResultSet r, String columnLabel, StatementContext ctx) throws SQLException {
        return Optional.ofNullable(r.getString(columnLabel))
                .map(JsonUtils::getJsonNodeFromString)
                .map(this::mapObject)
                .orElse(null);
    }
}
