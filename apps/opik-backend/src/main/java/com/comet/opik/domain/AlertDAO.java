package com.comet.opik.domain;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.AlertTrigger;
import com.comet.opik.api.AlertTriggerConfig;
import com.comet.opik.api.AlertTriggerConfigType;
import com.comet.opik.api.Webhook;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
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

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

        private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")
                .withZone(java.time.ZoneOffset.UTC);

        @Override
        public Alert map(ResultSet rs, StatementContext ctx) throws SQLException {
            // Parse webhook JSON object
            Webhook webhook = Optional.ofNullable(rs.getString("webhook"))
                    .map(JsonUtils::getJsonNodeFromString)
                    .map(this::mapWebhook)
                    .orElse(null);

            // Parse triggers JSON array
            List<AlertTrigger> triggers = Optional.ofNullable(rs.getString("triggers_json"))
                    .map(JsonUtils::getJsonNodeFromString)
                    .map(this::mapTriggers)
                    .orElse(null);

            // Build Alert object with embedded Webhook and Triggers
            return Alert.builder()
                    .id(UUID.fromString(rs.getString("id")))
                    .name(rs.getString("name"))
                    .enabled(rs.getBoolean("enabled"))
                    .webhook(webhook)
                    .triggers(triggers)
                    .createdAt(rs.getTimestamp("created_at") != null
                            ? rs.getTimestamp("created_at").toInstant()
                            : null)
                    .createdBy(rs.getString("created_by"))
                    .lastUpdatedAt(rs.getTimestamp("last_updated_at") != null
                            ? rs.getTimestamp("last_updated_at").toInstant()
                            : null)
                    .lastUpdatedBy(rs.getString("last_updated_by"))
                    .build();
        }

        private Webhook mapWebhook(JsonNode webhookNode) {
            try {
                // Parse webhook headers if present
                Map<String, String> webhookHeaders = Optional.ofNullable(webhookNode.get("headers"))
                        .map(this::parseHeaders)
                        .orElse(null);

                return Webhook.builder()
                        .id(UUID.fromString(webhookNode.get("id").asText()))
                        .url(webhookNode.get("url").asText())
                        .secretToken(webhookNode.get("secret_token").asText())
                        .headers(webhookHeaders)
                        .createdAt(Instant.from(FORMATTER.parse(webhookNode.get("created_at").asText())))
                        .createdBy(webhookNode.get("created_by").asText())
                        .lastUpdatedAt(Instant.from(FORMATTER.parse(webhookNode.get("last_updated_at").asText())))
                        .lastUpdatedBy(webhookNode.get("last_updated_by").asText())
                        .build();
            } catch (Exception e) {
                log.warn("Failed to parse webhook JSON: '{}'", webhookNode, e);
                return null;
            }
        }

        private Map<String, String> parseHeaders(JsonNode headersNode) {
            try {
                if (headersNode.isTextual()) {
                    String headersStr = headersNode.asText();
                    if (!headersStr.trim().isEmpty()) {
                        return JsonUtils.readValue(headersStr, MAP_TYPE_REF);
                    }
                } else if (headersNode.isObject()) {
                    return JsonUtils.MAPPER.convertValue(headersNode, MAP_TYPE_REF);
                }
            } catch (Exception e) {
                log.warn("Failed to parse webhook headers: '{}'", headersNode, e);
            }
            return Map.of();
        }

        private List<AlertTrigger> mapTriggers(JsonNode triggersNode) {
            try {
                if (triggersNode.isArray()) {
                    java.util.List<AlertTrigger> triggers = new java.util.ArrayList<>();
                    for (JsonNode triggerNode : triggersNode) {
                        AlertTrigger trigger = mapTrigger(triggerNode);
                        if (trigger != null) {
                            triggers.add(trigger);
                        }
                    }
                    return triggers;
                }
            } catch (Exception e) {
                log.warn("Failed to parse triggers JSON: '{}'", triggersNode, e);
            }
            return null;
        }

        private AlertTrigger mapTrigger(JsonNode triggerNode) {
            try {
                // Parse trigger configs if present
                List<AlertTriggerConfig> triggerConfigs = Optional.ofNullable(triggerNode.get("trigger_configs"))
                        .map(this::mapTriggerConfigs)
                        .orElse(null);

                return AlertTrigger.builder()
                        .id(UUID.fromString(triggerNode.get("id").asText()))
                        .alertId(UUID.fromString(triggerNode.get("alert_id").asText()))
                        .eventType(AlertEventType.fromString(triggerNode.get("event_type").asText()))
                        .triggerConfigs(triggerConfigs)
                        .createdAt(Instant.from(FORMATTER.parse(triggerNode.get("created_at").asText())))
                        .createdBy(triggerNode.get("created_by").asText())
                        .build();
            } catch (Exception e) {
                log.warn("Failed to parse trigger: '{}'", triggerNode, e);
                return null;
            }
        }

        private List<AlertTriggerConfig> mapTriggerConfigs(JsonNode configsNode) {
            try {
                if (configsNode.isArray()) {
                    java.util.List<AlertTriggerConfig> configs = new java.util.ArrayList<>();
                    for (JsonNode configNode : configsNode) {
                        AlertTriggerConfig config = mapTriggerConfig(configNode);
                        if (config != null) {
                            configs.add(config);
                        }
                    }
                    return configs;
                }
            } catch (Exception e) {
                log.warn("Failed to parse trigger configs: '{}'", configsNode, e);
            }
            return null;
        }

        private AlertTriggerConfig mapTriggerConfig(JsonNode configNode) {
            try {
                Map<String, String> configValue = Optional.ofNullable(configNode.get("config_value"))
                        .map(this::parseConfigValue)
                        .orElse(null);

                return AlertTriggerConfig.builder()
                        .id(UUID.fromString(configNode.get("id").asText()))
                        .alertTriggerId(UUID.fromString(configNode.get("alert_trigger_id").asText()))
                        .type(AlertTriggerConfigType.fromString(configNode.get("config_type").asText()))
                        .configValue(configValue)
                        .createdAt(Instant.from(FORMATTER.parse(configNode.get("created_at").asText())))
                        .createdBy(configNode.get("created_by").asText())
                        .lastUpdatedAt(Instant.from(FORMATTER.parse(configNode.get("last_updated_at").asText())))
                        .lastUpdatedBy(configNode.get("last_updated_by").asText())
                        .build();
            } catch (Exception e) {
                log.warn("Failed to parse trigger config: '{}'", configNode, e);
                return null;
            }
        }

        private Map<String, String> parseConfigValue(JsonNode configValueNode) {
            try {
                if (configValueNode.isTextual()) {
                    String configStr = configValueNode.asText();
                    if (!configStr.trim().isEmpty()) {
                        return JsonUtils.readValue(configStr, MAP_TYPE_REF);
                    }
                } else if (configValueNode.isObject()) {
                    return JsonUtils.MAPPER.convertValue(configValueNode, MAP_TYPE_REF);
                }
            } catch (Exception e) {
                log.warn("Failed to parse config value: '{}'", configValueNode, e);
            }
            return Map.of();
        }
    }
}
