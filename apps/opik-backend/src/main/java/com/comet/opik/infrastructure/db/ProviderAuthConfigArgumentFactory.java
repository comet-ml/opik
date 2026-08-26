package com.comet.opik.infrastructure.db;

import com.comet.opik.api.ProviderAuthConfig;
import com.comet.opik.infrastructure.EncryptionUtils;
import com.comet.opik.utils.JsonUtils;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;

import java.sql.Types;

/**
 * Binds a {@link ProviderAuthConfig} as its AES-GCM-encrypted JSON representation, so the recipe
 * (which can contain several secrets) is never stored or logged in plaintext. The read side lives
 * in {@code ProviderApiKeyRowMapper}.
 */
public class ProviderAuthConfigArgumentFactory extends AbstractArgumentFactory<ProviderAuthConfig> {

    public ProviderAuthConfigArgumentFactory() {
        super(Types.VARCHAR);
    }

    @Override
    protected Argument build(ProviderAuthConfig value, ConfigRegistry config) {
        return (position, statement, ctx) -> {
            if (value == null) {
                statement.setNull(position, Types.VARCHAR);
            } else {
                statement.setString(position, EncryptionUtils.encryptGcm(JsonUtils.writeValueAsString(value)));
            }
        };
    }
}
