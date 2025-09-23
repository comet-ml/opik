package com.comet.opik.domain;

import com.comet.opik.api.Alert;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(Alert.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
interface AlertDAO {

    @SqlUpdate("INSERT INTO alerts (id, name, description, condition_type, threshold_value, project_id, workspace_id, created_by, last_updated_by) "
            +
            "VALUES (:bean.id, :bean.name, :bean.description, :bean.conditionType, :bean.thresholdValue, :bean.projectId, :workspaceId, :bean.createdBy, :bean.lastUpdatedBy)")
    @GetGeneratedKeys
    Alert save(@Bind("workspaceId") String workspaceId, @BindMethods("bean") Alert alert);
}