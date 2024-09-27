package com.comet.opik.infrastructure.bi;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

interface UsageReportDAO {

    @SqlQuery("SELECT COUNT(*) > 0 FROM usage_information WHERE event_type = :eventType AND reported_at IS NOT NULL")
    boolean isEventReported(@Bind("eventType") String eventType);

    @SqlUpdate("INSERT INTO usage_information (event_type) VALUES (:eventType)")
    int addEvent(@Bind("eventType") String eventType);

    @SqlUpdate("UPDATE usage_information SET reported_at = NOW() WHERE event_type = :eventType")
    int markEventAsReported(@Bind("eventType") String eventType);

}
