package com.comet.opik.domain.threads;

import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterConstructorMapper(TraceThreadIdModel.class)
interface TraceThreadIdDAO {

    @SqlUpdate("""
                INSERT INTO project_trace_threads(id, project_id, thread_id, created_by, created_at)
                VALUES (:thread.id, :thread.projectId, :thread.threadId, :thread.createdBy, :thread.createdAt)
            """)
    void save(@BindMethods("thread") TraceThreadIdModel rule);

    @SqlQuery("""
                SELECT * FROM project_trace_threads
                WHERE project_id = :projectId AND thread_id = :threadId
            """)
    TraceThreadIdModel findByProjectIdAndThreadId(@Bind("projectId") UUID projectId, @Bind("threadId") String threadId);

    @SqlQuery("""
                SELECT * FROM project_trace_threads
                WHERE id = :id
            """)
    TraceThreadIdModel findByThreadModelId(@Bind("id") UUID threadModelId);

    @SqlQuery("""
                SELECT * FROM project_trace_threads
                WHERE id IN (<threadModelIds>)
            """)
    List<TraceThreadIdModel> findByThreadModelIds(@BindList("threadModelIds") List<UUID> threadModelIds);
}
