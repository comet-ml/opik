package com.comet.opik.domain;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.infrastructure.db.MapFlatArgumentFactory;
import lombok.NonNull;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Custom row mapper for ProviderApiKey that converts the sentinel value '__NULL__'
 * to actual null for the provider_name column.
 */
public class ProviderApiKeyRowMapper implements RowMapper<ProviderApiKey> {

    private final MapFlatArgumentFactory mapMapper = new MapFlatArgumentFactory();

    @Override
    public ProviderApiKey map(@NonNull ResultSet rs, @NonNull StatementContext ctx) throws SQLException {
        String providerName = rs.getString("provider_name");

        // Convert sentinel value to null
        if (LlmProviderApiKeyDAO.NULL_SENTINEL.equals(providerName)) {
            providerName = null;
        }

        return ProviderApiKey.builder()
                .id(rs.getObject("id", UUID.class))
                .provider(LlmProvider.valueOf(rs.getString("provider")))
                .apiKey(rs.getString("api_key"))
                .name(rs.getString("name"))
                .providerName(providerName)
                .headers(mapMapper.map(rs, "headers", ctx))
                .configuration(mapMapper.map(rs, "configuration", ctx))
                .baseUrl(rs.getString("base_url"))
                .createdAt(rs.getTimestamp("created_at").toInstant())
                .createdBy(rs.getString("created_by"))
                .lastUpdatedAt(rs.getTimestamp("last_updated_at").toInstant())
                .lastUpdatedBy(rs.getString("last_updated_by"))
                .build();
    }
}
