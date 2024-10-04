package com.comet.opik.infrastructure.bi;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

interface UsageReportDAO {

    @SqlQuery("SELECT COUNT(*) > 0 FROM usage_information WHERE event_type = :eventType")
    boolean isEventReported(@Bind("eventType") String eventType);

    @SqlUpdate("INSERT INTO usage_information (event_type) VALUES (:eventType)")
    int markEventAsReported(@Bind("eventType") String eventType);

}
