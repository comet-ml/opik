package com.comet.opik.domain;

import com.comet.opik.api.Alert;
import com.comet.opik.api.Webhook;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.io.UncheckedIOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

@RegisterRowMapper(AlertDAO.AlertWithWebhookRowMapper.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
interface AlertDAO {

    @SqlUpdate("INSERT INTO alerts (id, name, enabled, webhook_id, workspace_id, created_by, last_updated_by) "
            +
            "VALUES (:bean.id, :bean.name, :bean.enabled, :webhookId, :workspaceId, :bean.createdBy, :bean.lastUpdatedBy)")
    @GetGeneratedKeys
    Alert save(@Bind("workspaceId") String workspaceId, @BindMethods("bean") Alert alert,
            @Bind("webhookId") UUID webhookId);

    @SqlQuery("SELECT " +
            "a.id as alert_id, " +
            "a.name as alert_name, " +
            "a.enabled as alert_enabled, " +
            "a.created_at as alert_created_at, " +
            "a.created_by as alert_created_by, " +
            "a.last_updated_at as alert_last_updated_at, " +
            "a.last_updated_by as alert_last_updated_by, " +
            "w.id as webhook_id, " +
            "w.name as webhook_name, " +
            "w.url as webhook_url, " +
            "w.secret_token as webhook_secret_token, " +
            "w.headers as webhook_headers, " +
            "w.created_at as webhook_created_at, " +
            "w.created_by as webhook_created_by, " +
            "w.last_updated_at as webhook_last_updated_at, " +
            "w.last_updated_by as webhook_last_updated_by " +
            "FROM alerts a " +
            "JOIN webhooks w ON a.webhook_id = w.id " +
            "WHERE a.id = :id AND a.workspace_id = :workspaceId")
    Alert findById(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @Slf4j
    class AlertWithWebhookRowMapper implements RowMapper<Alert> {

        private static final TypeReference<Map<String, String>> MAP_TYPE_REF = new TypeReference<>() {
        };

        @Override
        public Alert map(ResultSet rs, StatementContext ctx) throws SQLException {
            // Parse webhook headers JSON to Map
            Map<String, String> webhookHeaders = null;
            String headersJson = rs.getString("webhook_headers");
            if (headersJson != null && !headersJson.trim().isEmpty()) {
                try {
                    webhookHeaders = JsonUtils.readValue(headersJson, MAP_TYPE_REF);
                } catch (UncheckedIOException e) {
                    log.warn("Failed to parse webhook headers JSON: '{}'", headersJson, e);
                    webhookHeaders = Map.of();
                }
            }

            // Build Webhook object
            Webhook webhook = Webhook.builder()
                    .id(UUID.fromString(rs.getString("webhook_id")))
                    .name(rs.getString("webhook_name"))
                    .url(rs.getString("webhook_url"))
                    .secretToken(rs.getString("webhook_secret_token"))
                    .headers(webhookHeaders)
                    .createdAt(rs.getTimestamp("webhook_created_at") != null
                            ? rs.getTimestamp("webhook_created_at").toInstant()
                            : null)
                    .createdBy(rs.getString("webhook_created_by"))
                    .lastUpdatedAt(rs.getTimestamp("webhook_last_updated_at") != null
                            ? rs.getTimestamp("webhook_last_updated_at").toInstant()
                            : null)
                    .lastUpdatedBy(rs.getString("webhook_last_updated_by"))
                    .build();

            // Build Alert object with embedded Webhook
            return Alert.builder()
                    .id(UUID.fromString(rs.getString("alert_id")))
                    .name(rs.getString("alert_name"))
                    .enabled(rs.getBoolean("alert_enabled"))
                    .webhook(webhook)
                    .createdAt(rs.getTimestamp("alert_created_at") != null
                            ? rs.getTimestamp("alert_created_at").toInstant()
                            : null)
                    .createdBy(rs.getString("alert_created_by"))
                    .lastUpdatedAt(rs.getTimestamp("alert_last_updated_at") != null
                            ? rs.getTimestamp("alert_last_updated_at").toInstant()
                            : null)
                    .lastUpdatedBy(rs.getString("alert_last_updated_by"))
                    .build();
        }
    }
}
