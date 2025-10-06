package com.comet.opik.domain;

import com.comet.opik.api.opensourcewelcomewizard.OpenSourceWelcomeWizardTracking;
import com.comet.opik.infrastructure.db.ListFlatArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.Optional;

@RegisterConstructorMapper(OpenSourceWelcomeWizardTracking.class)
@RegisterArgumentFactory(ListFlatArgumentFactory.class)
@RegisterColumnMapper(ListFlatArgumentFactory.class)
interface OpenSourceWelcomeWizardTrackingDAO {

    @SqlQuery("SELECT * FROM open_source_welcome_wizard_tracking WHERE workspace_id = :workspaceId")
    Optional<OpenSourceWelcomeWizardTracking> findByWorkspaceId(@Bind("workspaceId") String workspaceId);

    @SqlUpdate("INSERT INTO open_source_welcome_wizard_tracking " +
            "(workspace_id, completed, email, role, integrations, join_beta_program, submitted_at, created_by, last_updated_by) "
            +
            "VALUES (:bean.workspaceId, :bean.completed, :bean.email, :bean.role, " +
            "CAST(:bean.integrations AS JSON), :bean.joinBetaProgram, :bean.submittedAt, :bean.createdBy, :bean.lastUpdatedBy)")
    void save(@BindMethods("bean") OpenSourceWelcomeWizardTracking tracking);

    @SqlUpdate("UPDATE open_source_welcome_wizard_tracking SET " +
            "completed = :bean.completed, " +
            "email = :bean.email, " +
            "role = :bean.role, " +
            "integrations = CAST(:bean.integrations AS JSON), " +
            "join_beta_program = :bean.joinBetaProgram, " +
            "submitted_at = :bean.submittedAt, " +
            "last_updated_by = :bean.lastUpdatedBy " +
            "WHERE workspace_id = :bean.workspaceId")
    void update(@BindMethods("bean") OpenSourceWelcomeWizardTracking tracking);
}
