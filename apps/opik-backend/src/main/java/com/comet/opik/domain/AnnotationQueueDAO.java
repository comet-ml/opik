package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.Page;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindBean;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterRowMapper(AnnotationQueueRowMapper.class)
public interface AnnotationQueueDAO {

    @SqlUpdate("""
        INSERT INTO annotation_queues (id, name, description, status, created_by, project_id, template_id, 
                                     visible_fields, required_metrics, optional_metrics, instructions, due_date, 
                                     created_at, updated_at)
        VALUES (:id, :name, :description, :status, :createdBy, :projectId, :templateId, 
                :visibleFields, :requiredMetrics, :optionalMetrics, :instructions, :dueDate, 
                :createdAt, :updatedAt)
        """)
    @GetGeneratedKeys
    void insert(@BindBean AnnotationQueue annotationQueue);

    @SqlQuery("""
        SELECT aq.*, 
               COALESCE(qi_stats.total_items, 0) as total_items,
               COALESCE(qi_stats.completed_items, 0) as completed_items,
               COALESCE(sme_list.assigned_smes, '[]') as assigned_smes
        FROM annotation_queues aq
        LEFT JOIN (
            SELECT queue_id, 
                   COUNT(*) as total_items,
                   SUM(CASE WHEN status = 'completed' THEN 1 ELSE 0 END) as completed_items
            FROM queue_items 
            GROUP BY queue_id
        ) qi_stats ON aq.id = qi_stats.queue_id
        LEFT JOIN (
            SELECT queue_id,
                   JSON_ARRAYAGG(DISTINCT assigned_sme) as assigned_smes
            FROM queue_items 
            WHERE assigned_sme IS NOT NULL
            GROUP BY queue_id
        ) sme_list ON aq.id = sme_list.queue_id
        WHERE aq.id = :id AND aq.project_id = :projectId
        """)
    Optional<AnnotationQueue> findById(@Bind("id") UUID id, @Bind("projectId") UUID projectId);

    @SqlQuery("""
        SELECT aq.*, 
               COALESCE(qi_stats.total_items, 0) as total_items,
               COALESCE(qi_stats.completed_items, 0) as completed_items,
               COALESCE(sme_list.assigned_smes, '[]') as assigned_smes
        FROM annotation_queues aq
        LEFT JOIN (
            SELECT queue_id, 
                   COUNT(*) as total_items,
                   SUM(CASE WHEN status = 'completed' THEN 1 ELSE 0 END) as completed_items
            FROM queue_items 
            GROUP BY queue_id
        ) qi_stats ON aq.id = qi_stats.queue_id
        LEFT JOIN (
            SELECT queue_id,
                   JSON_ARRAYAGG(DISTINCT assigned_sme) as assigned_smes
            FROM queue_items 
            WHERE assigned_sme IS NOT NULL
            GROUP BY queue_id
        ) sme_list ON aq.id = sme_list.queue_id
        WHERE aq.project_id = :projectId
        ORDER BY aq.created_at DESC
        LIMIT :limit OFFSET :offset
        """)
    List<AnnotationQueue> findByProjectId(@Bind("projectId") UUID projectId, 
                                        @Bind("limit") int limit, 
                                        @Bind("offset") int offset);

    @SqlQuery("""
        SELECT COUNT(*) 
        FROM annotation_queues 
        WHERE project_id = :projectId
        """)
    long countByProjectId(@Bind("projectId") UUID projectId);

    @SqlUpdate("""
        UPDATE annotation_queues 
        SET name = :name, description = :description, template_id = :templateId,
            visible_fields = :visibleFields, required_metrics = :requiredMetrics, 
            optional_metrics = :optionalMetrics, instructions = :instructions, 
            due_date = :dueDate, updated_at = :updatedAt
        WHERE id = :id AND project_id = :projectId
        """)
    int update(@BindBean AnnotationQueue annotationQueue, @Bind("projectId") UUID projectId);

    @SqlUpdate("""
        UPDATE annotation_queues 
        SET status = :status, updated_at = :updatedAt
        WHERE id = :id AND project_id = :projectId
        """)
    int updateStatus(@Bind("id") UUID id, 
                    @Bind("projectId") UUID projectId,
                    @Bind("status") String status, 
                    @Bind("updatedAt") java.time.Instant updatedAt);

    @SqlUpdate("""
        DELETE FROM annotation_queues 
        WHERE id = :id AND project_id = :projectId
        """)
    int delete(@Bind("id") UUID id, @Bind("projectId") UUID projectId);

    // Public access method for SME interface (no project_id filter)
    @SqlQuery("""
        SELECT aq.*, 
               COALESCE(qi_stats.total_items, 0) as total_items,
               COALESCE(qi_stats.completed_items, 0) as completed_items,
               COALESCE(sme_list.assigned_smes, '[]') as assigned_smes
        FROM annotation_queues aq
        LEFT JOIN (
            SELECT queue_id, 
                   COUNT(*) as total_items,
                   SUM(CASE WHEN status = 'completed' THEN 1 ELSE 0 END) as completed_items
            FROM queue_items 
            GROUP BY queue_id
        ) qi_stats ON aq.id = qi_stats.queue_id
        LEFT JOIN (
            SELECT queue_id,
                   JSON_ARRAYAGG(DISTINCT assigned_sme) as assigned_smes
            FROM queue_items 
            WHERE assigned_sme IS NOT NULL
            GROUP BY queue_id
        ) sme_list ON aq.id = sme_list.queue_id
        WHERE aq.id = :id
        """)
    Optional<AnnotationQueue> findByIdPublic(@Bind("id") UUID id);

    default Page<AnnotationQueue> findByProjectIdPaginated(UUID projectId, int page, int size) {
        var offset = (page - 1) * size;
        var items = findByProjectId(projectId, size, offset);
        var total = countByProjectId(projectId);
        
        return Page.<AnnotationQueue>builder()
                .page(page)
                .size(size)
                .total(total)
                .content(items)
                .build();
    }
}