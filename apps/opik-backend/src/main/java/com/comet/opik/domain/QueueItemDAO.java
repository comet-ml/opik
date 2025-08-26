package com.comet.opik.domain;

import com.comet.opik.api.Page;
import com.comet.opik.api.QueueItem;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindBean;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterRowMapper(QueueItemRowMapper.class)
public interface QueueItemDAO {

    @SqlUpdate("""
        INSERT INTO queue_items (id, queue_id, item_type, item_id, status, assigned_sme, created_at)
        VALUES (:id, :queueId, :itemType, :itemId, :status, :assignedSme, :createdAt)
        """)
    @GetGeneratedKeys
    void insert(@BindBean QueueItem queueItem);

    @SqlBatch("""
        INSERT INTO queue_items (id, queue_id, item_type, item_id, status, assigned_sme, created_at)
        VALUES (:id, :queueId, :itemType, :itemId, :status, :assignedSme, :createdAt)
        ON DUPLICATE KEY UPDATE id = id
        """)
    void insertBatch(@BindBean List<QueueItem> queueItems);

    @SqlQuery("""
        SELECT qi.*
        FROM queue_items qi
        WHERE qi.id = :id AND qi.queue_id = :queueId
        """)
    Optional<QueueItem> findById(@Bind("id") UUID id, @Bind("queueId") UUID queueId);

    @SqlQuery("""
        SELECT qi.*
        FROM queue_items qi
        WHERE qi.queue_id = :queueId
        ORDER BY qi.created_at ASC
        LIMIT :limit OFFSET :offset
        """)
    List<QueueItem> findByQueueId(@Bind("queueId") UUID queueId, 
                                 @Bind("limit") int limit, 
                                 @Bind("offset") int offset);

    @SqlQuery("""
        SELECT COUNT(*) 
        FROM queue_items 
        WHERE queue_id = :queueId
        """)
    long countByQueueId(@Bind("queueId") UUID queueId);

    @SqlQuery("""
        SELECT qi.*
        FROM queue_items qi
        WHERE qi.queue_id = :queueId AND qi.assigned_sme = :smeId
        ORDER BY qi.created_at ASC
        LIMIT :limit OFFSET :offset
        """)
    List<QueueItem> findByQueueIdAndSme(@Bind("queueId") UUID queueId,
                                       @Bind("smeId") String smeId,
                                       @Bind("limit") int limit, 
                                       @Bind("offset") int offset);

    @SqlQuery("""
        SELECT COUNT(*) 
        FROM queue_items 
        WHERE queue_id = :queueId AND assigned_sme = :smeId
        """)
    long countByQueueIdAndSme(@Bind("queueId") UUID queueId, @Bind("smeId") String smeId);

    @SqlUpdate("""
        UPDATE queue_items 
        SET status = :status, assigned_sme = :assignedSme, completed_at = :completedAt
        WHERE id = :id AND queue_id = :queueId
        """)
    int updateStatus(@Bind("id") UUID id, 
                    @Bind("queueId") UUID queueId,
                    @Bind("status") String status,
                    @Bind("assignedSme") String assignedSme,
                    @Bind("completedAt") java.time.Instant completedAt);

    @SqlUpdate("""
        DELETE FROM queue_items 
        WHERE id = :id AND queue_id = :queueId
        """)
    int delete(@Bind("id") UUID id, @Bind("queueId") UUID queueId);

    @SqlUpdate("""
        DELETE FROM queue_items 
        WHERE queue_id = :queueId AND item_type = :itemType AND item_id = :itemId
        """)
    int deleteByItem(@Bind("queueId") UUID queueId, 
                    @Bind("itemType") String itemType, 
                    @Bind("itemId") String itemId);

    // Get next item for SME annotation
    @SqlQuery("""
        SELECT qi.*
        FROM queue_items qi
        WHERE qi.queue_id = :queueId 
        AND qi.status = 'pending'
        AND (qi.assigned_sme IS NULL OR qi.assigned_sme = :smeId)
        ORDER BY qi.created_at ASC
        LIMIT 1
        """)
    Optional<QueueItem> getNextItemForSme(@Bind("queueId") UUID queueId, @Bind("smeId") String smeId);

    default Page<QueueItem> findByQueueIdPaginated(UUID queueId, int page, int size) {
        var offset = (page - 1) * size;
        var items = findByQueueId(queueId, size, offset);
        var total = countByQueueId(queueId);
        
        return Page.<QueueItem>builder()
                .page(page)
                .size(size)
                .total(total)
                .content(items)
                .build();
    }

    default Page<QueueItem> findByQueueIdAndSmePaginated(UUID queueId, String smeId, int page, int size) {
        var offset = (page - 1) * size;
        var items = findByQueueIdAndSme(queueId, smeId, size, offset);
        var total = countByQueueIdAndSme(queueId, smeId);
        
        return Page.<QueueItem>builder()
                .page(page)
                .size(size)
                .total(total)
                .content(items)
                .build();
    }
}