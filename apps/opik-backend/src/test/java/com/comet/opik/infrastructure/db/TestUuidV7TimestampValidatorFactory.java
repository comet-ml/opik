package com.comet.opik.infrastructure.db;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.infrastructure.UuidValidationConfig;
import lombok.experimental.UtilityClass;

/**
 * Test-only factory for {@link UuidV7TimestampValidator}: builds it from the {@code uuidValidation}
 * section of {@code config-test.yml} so the validator under test stays in sync with the configuration
 * the app runs with, rather than drifting from a hardcoded copy.
 */
@UtilityClass
public class TestUuidV7TimestampValidatorFactory {

    private static final UuidValidationConfig CONFIG = TestConfigUtils.loadConfigTest().getUuidValidation();

    public UuidV7TimestampValidator create() {
        return new UuidV7TimestampValidator(CONFIG);
    }
}
