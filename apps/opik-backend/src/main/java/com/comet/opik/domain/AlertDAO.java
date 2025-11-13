package com.comet.opik.domain;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.AlertTrigger;
import com.comet.opik.api.AlertTriggerConfig;
import com.comet.opik.api.AlertTriggerConfigType;
import com.comet.opik.api.AlertType;
import com.comet.opik.api.Webhook;
import com.comet.opik.infrastructure.db.AlertTypeArgumentFactory;
import com.comet.opik.infrastructure.db.AlertTypeColumnMapper;
import com.comet.opik.infrastructure.db.MapFlatArgumentFactory;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMap;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RegisterRowMapper(AlertDAO.AlertWithWebhookRowMapper.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(MapFlatArgumentFactory.class)
@RegisterColumnMapper(MapFlatArgumentFactory.class)
@RegisterArgumentFactory(AlertTypeArgumentFactory.class)
@RegisterColumnMapper(AlertTypeColumnMapper.class)
interface AlertDAO {

    String FIND = """
            WITH target_alerts AS (
                SELECT
                    a.id as id,
                    a.name as name,
                    a.enabled as enabled,
                    a.alert_type as alert_type,
                    a.metadata as metadata,
                    a.created_at as created_at,
                    a.created_by as created_by,
                    a.last_updated_at as last_updated_at,
                    a.last_updated_by as last_updated_by,
                    a.workspace_id as workspace_id,
                    w.id as webhook_id,
                    w.url as webhook_url,
                    w.secret_token as webhook_secret_token,
                    w.headers as webhook_headers,
                    w.created_at as webhook_created_at,
                    w.created_by as webhook_created_by,
                    w.last_updated_at as webhook_last_updated_at,
                    w.last_updated_by as webhook_last_updated_by
                FROM alerts a
                JOIN webhooks w ON a.webhook_id = w.id
                WHERE a.workspace_id = :workspaceId
                    <if(id)> AND a.id = :id <endif>
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
            LEFT JOIN target_triggers t
                ON a.id = t.alert_id
            <if(filters)> WHERE <filters> <endif>
            ORDER BY <if(sort_fields)> <sort_fields>, <endif> id DESC
            <if(limit)> LIMIT :limit <endif>
            <if(offset)> OFFSET :offset <endif>;
            """;

    @SqlUpdate("""
            INSERT INTO alerts (id, name, enabled, alert_type, metadata, webhook_id, workspace_id, created_by, last_updated_by, created_at)
            VALUES (:bean.id, :bean.name, :bean.enabled, :bean.alertType, :bean.metadata, :webhookId, :workspaceId, :bean.createdBy, :bean.lastUpdatedBy, COALESCE(:bean.createdAt, CURRENT_TIMESTAMP(6)))
            """)
    void save(@Bind("workspaceId") String workspaceId, @BindMethods("bean") Alert alert,
            @Bind("webhookId") UUID webhookId);

    @SqlQuery(FIND)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    Alert findById(@Define("id") @Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlQuery(FIND)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<Alert> find(@Bind("workspaceId") String workspaceId,
            @Define("offset") @Bind("offset") int offset, @Define("limit") @Bind("limit") int limit,
            @Define("sort_fields") @Bind("sort_fields") String sortingFields,
            @Define("filters") String filters,
            @BindMap Map<String, Object> filterMapping);

    @SqlQuery("""
            WITH target_alerts AS (
                SELECT
                    a.id as id,
                    a.name as name,
                    a.enabled as enabled,
                    a.alert_type as alert_type,
                    a.metadata as metadata,
                    a.created_at as created_at,
                    a.created_by as created_by,
                    a.last_updated_at as last_updated_at,
                    a.last_updated_by as last_updated_by,
                    a.workspace_id as workspace_id,
                    w.id as webhook_id,
                    w.url as webhook_url,
                    w.secret_token as webhook_secret_token,
                    w.headers as webhook_headers,
                    w.created_at as webhook_created_at,
                    w.created_by as webhook_created_by,
                    w.last_updated_at as webhook_last_updated_at,
                    w.last_updated_by as webhook_last_updated_by
                FROM alerts a
                JOIN webhooks w ON a.webhook_id = w.id
                WHERE <if(workspaceId)> a.workspace_id = :workspaceId AND <endif> a.enabled = true
            ),
            trigger_ids AS (
                SELECT id
                FROM alert_triggers
                WHERE alert_id IN (SELECT id FROM target_alerts) AND event_type IN (<eventTypes>)
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
            JOIN target_triggers t
                ON a.id = t.alert_id;
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<Alert> findByWorkspaceAndEventTypes(@Define("workspaceId") @Bind("workspaceId") String workspaceId,
            @BindList("eventTypes") Set<String> eventTypes);

    @SqlQuery("""
             WITH target_alerts AS (
                SELECT
                    a.id as id,
                    a.name as name,
                    a.alert_type as alert_type,
                    a.created_at as created_at,
                    a.created_by as created_by,
                    a.last_updated_at as last_updated_at,
                    a.last_updated_by as last_updated_by,
                    w.url as webhook_url,
                    w.secret_token as webhook_secret_token
                FROM alerts a
                JOIN webhooks w ON a.webhook_id = w.id
                WHERE a.workspace_id = :workspaceId
            )
            SELECT
                count(id)
            FROM target_alerts
            <if(filters)> WHERE <filters> <endif>
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long count(@Bind("workspaceId") String workspaceId,
            @Define("filters") String filters,
            @BindMap Map<String, Object> filterMapping);

    @SqlUpdate("""
                    DELETE a, w, atr, atrc
                    FROM alerts a
                    JOIN webhooks w ON a.webhook_id = w.id
                    LEFT JOIN alert_triggers atr ON a.id = atr.alert_id
                    LEFT JOIN alert_trigger_configs atrc ON atr.id = atrc.alert_trigger_id
                    WHERE  a.id IN (<ids>) AND a.workspace_id = :workspaceId;
            """)
    void delete(@BindList("ids") Set<UUID> ids, @Bind("workspaceId") String workspaceId);

    @Slf4j
    class AlertWithWebhookRowMapper implements RowMapper<Alert> {

        private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS")
                .withZone(java.time.ZoneOffset.UTC);
        private static final MapFlatArgumentFactory MAP_MAPPER = new MapFlatArgumentFactory();

        @Override
        public Alert map(ResultSet rs, StatementContext ctx) throws SQLException {
            // Parse webhook headers JSON to Map using column mapper
            Map<String, String> webhookHeaders = MAP_MAPPER.map(rs, "webhook_headers", ctx);

            // Build Webhook object
            Webhook webhook = Webhook.builder()
                    .id(UUID.fromString(rs.getString("webhook_id")))
                    .url(rs.getString("webhook_url"))
                    .secretToken(rs.getString("webhook_secret_token"))
                    .headers(webhookHeaders)
                    .createdAt(rs.getTimestamp("webhook_created_at").toInstant())
                    .createdBy(rs.getString("webhook_created_by"))
                    .lastUpdatedAt(rs.getTimestamp("webhook_last_updated_at").toInstant())
                    .lastUpdatedBy(rs.getString("webhook_last_updated_by"))
                    .build();

            // Parse triggers JSON array
            List<AlertTrigger> triggers = Optional.ofNullable(rs.getString("triggers_json"))
                    .map(JsonUtils::getJsonNodeFromString)
                    .map(this::mapTriggers)
                    .orElse(null);

            // Parse alert_type using column mapper
            AlertType alertType = ctx.findColumnMapperFor(AlertType.class)
                    .map(mapper -> {
                        try {
                            return mapper.map(rs, "alert_type", ctx);
                        } catch (SQLException e) {
                            log.warn("Failed to map alert_type column", e);
                            return null;
                        }
                    })
                    .orElse(null);

            // Parse metadata JSON to Map using column mapper
            Map<String, String> metadata = MAP_MAPPER.map(rs, "metadata", ctx);

            // Build Alert object with embedded Webhook and Triggers
            return Alert.builder()
                    .id(UUID.fromString(rs.getString("id")))
                    .name(rs.getString("name"))
                    .enabled(rs.getBoolean("enabled"))
                    .alertType(alertType)
                    .metadata(metadata)
                    .webhook(webhook)
                    .triggers(triggers)
                    .createdAt(rs.getTimestamp("created_at").toInstant())
                    .createdBy(rs.getString("created_by"))
                    .lastUpdatedAt(rs.getTimestamp("last_updated_at").toInstant())
                    .lastUpdatedBy(rs.getString("last_updated_by"))
                    .workspaceId(rs.getString("workspace_id"))
                    .build();
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
                        return JsonUtils.readValue(configStr, MapFlatArgumentFactory.TYPE_REFERENCE);
                    }
                } else if (configValueNode.isObject()) {
                    return JsonUtils.convertValue(configValueNode, MapFlatArgumentFactory.TYPE_REFERENCE);
                }
            } catch (Exception e) {
                log.warn("Failed to parse config value: '{}'", configValueNode, e);
            }
            return Map.of();
        }
    }
}
