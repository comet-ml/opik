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

    @SqlQuery("""
            WITH target_alerts AS (
                SELECT *
                FROM alerts
                WHERE id = :id AND workspace_id = :workspaceId
            ),
            target_webhooks AS (
                SELECT
                    JSON_OBJECT(
                        'id', id,
                        'url', url,
                        'secret_token', secret_token,
                        'headers', headers,
                        'created_at', created_at,
                        'created_by', created_by,
                        'last_updated_at', last_updated_at,
                        'last_updated_by', last_updated_by
                    ) AS webhook,
                    id
                FROM webhooks
                WHERE workspace_id = :workspaceId
            ),
            trigger_ids AS (
                SELECT id
                FROM alert_triggers
                WHERE alert_id IN (SELECT id FROM target_alerts)
            ),
            trigger_configs AS (
                SELECT
                    tc.alert_trigger_id AS alert_trigger_id,
                    JSON_ARRAYAGG(tc.trigger_config_json) AS trigger_config_json
                FROM (
                    SELECT
                        JSON_OBJECT(
                            'id', id,
                            'alert_trigger_id', alert_trigger_id,
                            'config_type', config_type,
                            'config_value', config_value,
                            'created_at', created_at,
                            'created_by', created_by,
                            'last_updated_at', last_updated_at,
                            'last_updated_by', last_updated_by
                        ) AS trigger_config_json,
                        alert_trigger_id
                    FROM alert_trigger_configs
                    WHERE alert_trigger_id IN (SELECT id FROM trigger_ids)
                ) AS tc
                GROUP BY tc.alert_trigger_id
            ),
            target_triggers AS (
                SELECT
                    tj.alert_id AS alert_id,
                    JSON_ARRAYAGG(tj.trigger_json) AS triggers_json
                FROM (
                    SELECT
                        JSON_OBJECT(
                            'id', at.id,
                            'alert_id', at.alert_id,
                            'event_type', at.event_type,
                            'trigger_configs', tc.trigger_config_json,
                            'created_at', at.created_at,
                            'created_by', at.created_by
                        ) AS trigger_json,
                        at.alert_id AS alert_id
                    FROM alert_triggers at
                    LEFT JOIN trigger_configs tc
                        ON at.id = tc.alert_trigger_id
                    WHERE at.id IN (SELECT id FROM trigger_ids)
                ) AS tj
                GROUP BY tj.alert_id
            )
            SELECT *
            FROM target_alerts a
            JOIN target_webhooks w
                ON a.webhook_id = w.id
            LEFT JOIN target_triggers t
                ON a.id = t.alert_id;;
            """)
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
