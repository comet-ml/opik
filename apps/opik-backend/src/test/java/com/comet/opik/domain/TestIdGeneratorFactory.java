package com.comet.opik.domain;

import com.comet.opik.infrastructure.db.TestUuidV7TimestampValidatorFactory;
import lombok.experimental.UtilityClass;

/**
 * Test-only factory exposing the package-private {@link IdGeneratorImpl} to tests in other packages
 * that need a concrete {@link IdGenerator} (e.g. to mint deterministic UUIDv7 ids).
 */
@UtilityClass
public class TestIdGeneratorFactory {

    public IdGenerator create() {
        var uuidV7TimestampValidator = TestUuidV7TimestampValidatorFactory.create();
        return new IdGeneratorImpl(uuidV7TimestampValidator);
    }
}
