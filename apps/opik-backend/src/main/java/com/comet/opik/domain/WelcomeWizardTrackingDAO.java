package com.comet.opik.domain;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

interface WelcomeWizardTrackingDAO {

    String EVENT_TYPE_PREFIX = "welcome_wizard_";

    @SqlQuery("SELECT COUNT(*) > 0 FROM usage_information WHERE event_type = CONCAT(:eventTypePrefix, :workspaceId)")
    boolean isCompleted(@Bind("eventTypePrefix") String eventTypePrefix, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("INSERT INTO usage_information (event_type) VALUES (CONCAT(:eventTypePrefix, :workspaceId))")
    int markCompleted(@Bind("eventTypePrefix") String eventTypePrefix, @Bind("workspaceId") String workspaceId);
}
