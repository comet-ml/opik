package com.comet.opik.domain.mcpoauth;

import com.comet.opik.infrastructure.db.SetFlatArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.Optional;

@RegisterConstructorMapper(McpOAuthClient.class)
@RegisterArgumentFactory(SetFlatArgumentFactory.class)
@RegisterColumnMapper(SetFlatArgumentFactory.class)
interface McpOAuthClientDAO {

    @SqlUpdate("INSERT INTO mcp_oauth_clients (client_id, name, redirect_uris, logo_uri, owner_user_name) "
            +
            "VALUES (:bean.clientId, :bean.name, :bean.redirectUris, :bean.logoUri, :bean.ownerUserName)")
    void save(@BindMethods("bean") McpOAuthClient client);

    @SqlQuery("SELECT * FROM mcp_oauth_clients WHERE client_id = :clientId")
    McpOAuthClient findActiveById(@Bind("clientId") String clientId);

    default Optional<McpOAuthClient> fetchActive(String clientId) {
        return Optional.ofNullable(findActiveById(clientId));
    }
}
