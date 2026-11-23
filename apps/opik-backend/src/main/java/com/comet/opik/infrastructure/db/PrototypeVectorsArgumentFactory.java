package com.comet.opik.infrastructure.db;

import com.comet.opik.domain.topics.PrototypeVectors;
import org.jdbi.v3.core.argument.AbstractArgumentFactory;
import org.jdbi.v3.core.argument.Argument;
import org.jdbi.v3.core.config.ConfigRegistry;

import java.sql.Types;

/** Binds {@link PrototypeVectors} as a vector BLOB; see {@link PrototypeVectorsCodec}. */
public class PrototypeVectorsArgumentFactory extends AbstractArgumentFactory<PrototypeVectors> {

    public PrototypeVectorsArgumentFactory() {
        super(Types.BLOB);
    }

    @Override
    protected Argument build(PrototypeVectors value, ConfigRegistry config) {
        return (position, statement, ctx) -> statement.setBytes(position, PrototypeVectorsCodec.encode(value));
    }
}
