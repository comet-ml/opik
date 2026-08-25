package com.comet.opik.infrastructure.db;

import com.comet.opik.api.EncryptedAuthConfig;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;

import java.sql.Types;

/**
 * Binds the wrapper's persistence form: the original ciphertext when untouched (no pointless
 * re-encryption on updates that never parsed it), a fresh AES-GCM encryption otherwise.
 */
public class EncryptedAuthConfigArgumentFactory extends AbstractArgumentFactory<EncryptedAuthConfig> {

    public EncryptedAuthConfigArgumentFactory() {
        super(Types.VARCHAR);
    }

    @Override
    protected Argument build(EncryptedAuthConfig value, ConfigRegistry config) {
        return (position, statement, ctx) -> {
            if (value == null) {
                statement.setNull(position, Types.VARCHAR);
            } else {
                statement.setString(position, value.ciphertextOrEncrypt());
            }
        };
    }
}
