package com.comet.opik.domain;

import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.Optional;
import java.util.UUID;

@RegisterConstructorMapper(ProviderApiKey.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
public interface ProviderApiKeyDAO {

    @SqlUpdate("INSERT INTO provider_api_key (id, provider, workspace_id, api_key, created_by, last_updated_by) VALUES (:bean.id, :bean.provider, :workspaceId, :bean.apiKey, :bean.createdBy, :bean.lastUpdatedBy)")
    void save(@Bind("workspaceId") String workspaceId,
              @BindMethods("bean") ProviderApiKey providerApiKey);

    @SqlUpdate("UPDATE provider_api_key SET " +
            "api_key = :apiKey, " +
            "last_updated_by = :lastUpdatedBy " +
            "WHERE id = :id AND workspace_id = :workspaceId")
    void update(@Bind("id") UUID id,
                @Bind("workspaceId") String workspaceId,
                @Bind("apiKey") String encryptedApiKey,
                @Bind("lastUpdatedBy") String lastUpdatedBy);

    @SqlQuery("SELECT * FROM provider_api_key WHERE id = :id AND workspace_id = :workspaceId")
    ProviderApiKey findById(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    default Optional<ProviderApiKey> fetch(UUID id, String workspaceId) {
        return Optional.ofNullable(findById(id, workspaceId));
    }
}
