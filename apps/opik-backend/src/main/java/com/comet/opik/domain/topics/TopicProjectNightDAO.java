package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.LocalDate;
import java.util.Collection;
import java.util.UUID;

/**
 * The night fields of a project's {@code topic_projects} row. The night writes only rows that exist: a project with a
 * topic list always has one. Each night logs its decisions and emits metrics; the row keeps no history.
 */
@RegisterArgumentFactory(UUIDArgumentFactory.class)
interface TopicProjectNightDAO {

    /** The night looked at the projects: updated or skipped them, for any reason. */
    @SqlUpdate("""
            UPDATE topic_projects SET last_night = :night, not_reached_nights = 0
            WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)
            """)
    int markLooked(@Bind("workspaceId") String workspaceId, @BindList("projectIds") Collection<UUID> projectIds,
            @Bind("night") LocalDate night);

    /** The night's budget ended before the projects. A night run again by hand counts again. */
    @SqlUpdate("""
            UPDATE topic_projects SET not_reached_nights = not_reached_nights + 1
            WHERE workspace_id = :workspaceId AND project_id IN (<projectIds>)
            """)
    int markNotReached(@Bind("workspaceId") String workspaceId, @BindList("projectIds") Collection<UUID> projectIds);

}
