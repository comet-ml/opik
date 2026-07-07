package com.comet.opik.domain;

import lombok.Builder;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;

import java.util.List;

/**
 * user_email -> user_uuid mappings for Cost Intelligence, populated alongside cipx_trace_identities
 * from the cipx trace identity. The retrieval service (ai-cost-backend) resolves a caller's email to
 * user_uuid(s) here and then filters ClickHouse by user_uuid only (the cipx_trace_identities
 * primary-key prefix). The pair is the primary key: INSERT IGNORE keeps re-ingestion idempotent, and
 * one email may map to several uuids over time.
 */
public interface CipxUserMappingDAO {

    @Builder(toBuilder = true)
    record UserMapping(String userEmail, String userUuid) {
    }

    @SqlBatch("INSERT IGNORE INTO cipx_user_mappings (user_email, user_uuid) VALUES (:mapping.userEmail, :mapping.userUuid)")
    void save(@BindMethods("mapping") List<UserMapping> mappings);
}
