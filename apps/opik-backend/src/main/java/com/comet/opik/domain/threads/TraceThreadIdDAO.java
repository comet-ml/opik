package com.comet.opik.domain.threads;

import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;

import java.util.List;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterConstructorMapper(TraceThreadIdModel.class)
interface TraceThreadIdDAO {

    @SqlBatch("""
                INSERT INTO project_trace_threads(id, project_id, thread_id, created_by, created_at)
                VALUES (:thread.id, :thread.projectId, :thread.threadId, :thread.createdBy, :thread.createdAt)
            """)
    void save(@BindMethods("thread") List<TraceThreadIdModel> threads);

    default void save(TraceThreadIdModel thread) {
        save(List.of(thread));
    }

    @SqlQuery("""
                SELECT * FROM project_trace_threads
                WHERE project_id = :projectId AND thread_id IN (<threadIds>)
            """)
    List<TraceThreadIdModel> findByProjectIdAndThreadIds(@Bind("projectId") UUID projectId,
            @BindList("threadIds") List<String> threadIds);

    default TraceThreadIdModel findByProjectIdAndThreadId(UUID projectId, String threadId) {
        return findByProjectIdAndThreadIds(projectId, List.of(threadId)).stream().findFirst().orElse(null);
    }

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
