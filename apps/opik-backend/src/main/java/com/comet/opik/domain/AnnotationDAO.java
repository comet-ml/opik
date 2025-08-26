package com.comet.opik.domain;

import com.comet.opik.api.Annotation;
import org.jdbi.v3.sqlobject.config.RegisterRowMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindBean;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RegisterRowMapper(AnnotationRowMapper.class)
public interface AnnotationDAO {

    @SqlQuery("SELECT * FROM annotations WHERE id = :id")
    Optional<Annotation> findById(@Bind("id") UUID id);

    @SqlQuery("SELECT * FROM annotations WHERE queue_item_id = :queueItemId ORDER BY created_at DESC")
    List<Annotation> findByQueueItemId(@Bind("queueItemId") UUID queueItemId);

    @SqlQuery("SELECT * FROM annotations WHERE sme_id = :smeId ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    List<Annotation> findBySmeId(@Bind("smeId") String smeId, @Bind("limit") int limit, @Bind("offset") int offset);

    @SqlQuery("SELECT * FROM annotations WHERE queue_item_id = :queueItemId AND sme_id = :smeId")
    Optional<Annotation> findByQueueItemIdAndSmeId(@Bind("queueItemId") UUID queueItemId, @Bind("smeId") String smeId);

    @SqlUpdate("INSERT INTO annotations (id, queue_item_id, sme_id, metrics, comment, created_at, updated_at) " +
               "VALUES (:id, :queueItemId, :smeId, :metrics, :comment, :createdAt, :updatedAt)")
    @GetGeneratedKeys
    Annotation create(@BindBean Annotation annotation);

    @SqlUpdate("UPDATE annotations SET metrics = :metrics, comment = :comment, updated_at = :updatedAt " +
               "WHERE id = :id")
    Annotation update(@BindBean Annotation annotation);

    @SqlUpdate("DELETE FROM annotations WHERE id = :id")
    void delete(@Bind("id") UUID id);

    @SqlUpdate("DELETE FROM annotations WHERE queue_item_id = :queueItemId")
    void deleteByQueueItemId(@Bind("queueItemId") UUID queueItemId);

    @SqlQuery("SELECT COUNT(*) FROM annotations WHERE queue_item_id = :queueItemId")
    int countByQueueItemId(@Bind("queueItemId") UUID queueItemId);

    @SqlQuery("SELECT COUNT(*) FROM annotations WHERE sme_id = :smeId")
    int countBySmeId(@Bind("smeId") String smeId);
}