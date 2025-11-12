package com.comet.opik.infrastructure.db;

import com.comet.opik.domain.IdGenerator;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;

import java.time.Instant;
import java.util.UUID;

public class IdGeneratorImpl implements IdGenerator {

    private static final TimeBasedEpochGenerator generator = Generators.timeBasedEpochGenerator();

    @Override
    public UUID generateId() {
        return generator.generate();
    }

    @Override
    public UUID getTimeOrderedEpoch(long epochMilli) {
        return generator.construct(epochMilli);
    }

    @Override
    public UUID generateId(Instant timestamp) {
        return getTimeOrderedEpoch(timestamp.toEpochMilli());
    }
}
