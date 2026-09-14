package com.comet.opik.domain.evaluators;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.evaluators.EvalTriggerScope;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;

/**
 * Hand-written rather than a constructor mapper, for the same reason the evaluators use one: the model
 * carries fields that are not columns of either table. {@code projectIds} is the junction table's business,
 * and a router belongs to exactly one project, so the single id the query joins in stands for both.
 */
public class AutomationRuleAnnotationQueueRouterRowMapper
        implements
            RowMapper<AutomationRuleAnnotationQueueRouterModel> {

    @Override
    public AutomationRuleAnnotationQueueRouterModel map(ResultSet rs, StatementContext ctx) throws SQLException {
        UUID projectId = UUID.fromString(rs.getString("project_id"));
        String triggerScope = rs.getString("trigger_scope");

        return AutomationRuleAnnotationQueueRouterModel.builder()
                .id(UUID.fromString(rs.getString("id")))
                .projectId(projectId)
                .projectIds(Set.of(projectId))
                .name(rs.getString("name"))
                .samplingRate(rs.getFloat("sampling_rate"))
                .enabled(rs.getBoolean("enabled"))
                .triggerScope(triggerScope == null ? null : EvalTriggerScope.fromString(triggerScope))
                .filters(rs.getString("filters"))
                .queueId(UUID.fromString(rs.getString("queue_id")))
                .scope(AnnotationQueue.AnnotationScope.fromString(rs.getString("scope")))
                .conditions(rs.getString("conditions"))
                .maxItemsInQueue(rs.getObject("max_items_in_queue", Integer.class))
                .createdAt(rs.getTimestamp("created_at").toInstant())
                .createdBy(rs.getString("created_by"))
                .lastUpdatedAt(rs.getTimestamp("last_updated_at").toInstant())
                .lastUpdatedBy(rs.getString("last_updated_by"))
                .build();
    }
}
