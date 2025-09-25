package com.comet.opik.domain;

import com.comet.opik.api.Alert;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.GetGeneratedKeys;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.UUID;

@RegisterConstructorMapper(Alert.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
interface AlertDAO {

    @SqlUpdate("INSERT INTO alerts (id, name, enabled, webhook_id, workspace_id, created_by, last_updated_by) "
            +
            "VALUES (:bean.id, :bean.name, :bean.enabled, :bean.webhookId, :workspaceId, :bean.createdBy, :bean.lastUpdatedBy)")
    @GetGeneratedKeys
    Alert save(@Bind("workspaceId") String workspaceId, @BindMethods("bean") Alert alert);

    @SqlQuery("SELECT * FROM alerts WHERE id = :id AND workspace_id = :workspaceId")
    Alert findById(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);
}
