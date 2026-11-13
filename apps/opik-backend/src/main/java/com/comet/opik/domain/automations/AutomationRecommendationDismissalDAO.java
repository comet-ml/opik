package com.comet.opik.domain.automations;

import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Recommendations a project hid. Ids are deterministic, so hiding one hides every recomputation of it. */
@RegisterArgumentFactory(UUIDArgumentFactory.class)
public interface AutomationRecommendationDismissalDAO {

    @SqlUpdate("""
            INSERT INTO automation_recommendation_dismissals
                (workspace_id, recommendation_id, project_id, dismissed_until, created_by)
            VALUES (:workspaceId, :recommendationId, :projectId, :dismissedUntil, :userName)
            ON DUPLICATE KEY UPDATE dismissed_until = :dismissedUntil
            """)
    void dismiss(@Bind("workspaceId") String workspaceId,
            @Bind("recommendationId") UUID recommendationId,
            @Bind("projectId") UUID projectId,
            @Bind("dismissedUntil") Instant dismissedUntil,
            @Bind("userName") String userName);

    @SqlQuery("""
            SELECT recommendation_id FROM automation_recommendation_dismissals
            WHERE workspace_id = :workspaceId AND project_id = :projectId AND dismissed_until > :now
            """)
    Set<UUID> findDismissed(@Bind("workspaceId") String workspaceId,
            @Bind("projectId") UUID projectId,
            @Bind("now") Instant now);
}
