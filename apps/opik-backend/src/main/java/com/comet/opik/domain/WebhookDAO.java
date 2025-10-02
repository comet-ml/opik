package com.comet.opik.domain;

import com.comet.opik.api.Webhook;
import com.comet.opik.infrastructure.db.MapFlatArgumentFactory;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

@RegisterConstructorMapper(Webhook.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(MapFlatArgumentFactory.class)
@RegisterColumnMapper(MapFlatArgumentFactory.class)
public interface WebhookDAO {

    @SqlUpdate("INSERT INTO webhooks (id, name, url, secret_token, headers, workspace_id, created_by, last_updated_by, created_at) "
            +
            "VALUES (:bean.id, :bean.name, :bean.url, :bean.secretToken, :bean.headers, :workspaceId, :bean.createdBy, :bean.lastUpdatedBy, COALESCE(:bean.createdAt, CURRENT_TIMESTAMP(6)))")
    void save(@Bind("workspaceId") String workspaceId, @BindMethods("bean") Webhook webhook);
}
