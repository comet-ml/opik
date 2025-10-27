package com.comet.opik.domain;

import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.api.ProviderApiKeyUpdate;
import com.comet.opik.infrastructure.db.MapFlatArgumentFactory;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RegisterConstructorMapper(ProviderApiKey.class)
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(MapFlatArgumentFactory.class)
@RegisterColumnMapper(MapFlatArgumentFactory.class)
public interface LlmProviderApiKeyDAO {

    @SqlUpdate("INSERT INTO llm_provider_api_key (id, provider, workspace_id, api_key, name, provider_name, created_by, last_updated_by, headers, base_url, configuration) "
            +
            "VALUES (:bean.id, :bean.provider, :workspaceId, :bean.apiKey, :bean.name, :bean.providerName, :bean.createdBy, :bean.lastUpdatedBy, :bean.headers, :bean.baseUrl, :bean.configuration)")
    void save(@Bind("workspaceId") String workspaceId,
            @BindMethods("bean") ProviderApiKey providerApiKey);

    @SqlUpdate("UPDATE llm_provider_api_key SET " +
            "api_key = CASE WHEN :bean.apiKey IS NULL THEN api_key ELSE :bean.apiKey END, " +
            "name = CASE WHEN :bean.name IS NULL THEN name ELSE :bean.name END, " +
            "provider_name = CASE WHEN :bean.providerName IS NULL THEN provider_name ELSE :bean.providerName END, " +
            "headers = CASE WHEN :bean.headers IS NULL THEN headers ELSE :bean.headers END, " +
            "base_url = CASE WHEN :bean.baseUrl IS NULL THEN base_url ELSE :bean.baseUrl END, " +
            "configuration = CASE WHEN :bean.configuration IS NULL THEN configuration ELSE :bean.configuration END, " +
            "last_updated_by = :lastUpdatedBy " +
            "WHERE id = :id AND workspace_id = :workspaceId")
    void update(@Bind("id") UUID id,
            @Bind("workspaceId") String workspaceId,
            @Bind("lastUpdatedBy") String lastUpdatedBy,
            @BindMethods("bean") ProviderApiKeyUpdate providerApiKeyUpdate);

    @SqlQuery("SELECT * FROM llm_provider_api_key WHERE id = :id AND workspace_id = :workspaceId")
    ProviderApiKey findById(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT * FROM llm_provider_api_key " +
            " WHERE workspace_id = :workspaceId ")
    List<ProviderApiKey> find(@Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT * FROM llm_provider_api_key WHERE workspace_id = :workspaceId AND provider = :provider AND provider_name IS NULL LIMIT 1")
    Optional<ProviderApiKey> findByProvider(
            @Bind("workspaceId") String workspaceId,
            @Bind("provider") String provider);

    @SqlQuery("SELECT * FROM llm_provider_api_key WHERE workspace_id = :workspaceId AND provider = :provider " +
            "AND provider_name = :providerName LIMIT 1")
    Optional<ProviderApiKey> findByProviderAndProviderName(
            @Bind("workspaceId") String workspaceId,
            @Bind("provider") String provider,
            @Bind("providerName") String providerName);

    default Optional<ProviderApiKey> findByProviderAndName(
            String workspaceId,
            String provider,
            String providerName) {
        if (providerName == null) {
            return findByProvider(workspaceId, provider);
        } else {
            return findByProviderAndProviderName(workspaceId, provider, providerName);
        }
    }

    @SqlUpdate("DELETE FROM llm_provider_api_key WHERE id IN (<ids>) AND workspace_id = :workspaceId")
    void delete(@BindList("ids") Set<UUID> ids, @Bind("workspaceId") String workspaceId);

    default Optional<ProviderApiKey> fetch(UUID id, String workspaceId) {
        return Optional.ofNullable(findById(id, workspaceId));
    }
}
