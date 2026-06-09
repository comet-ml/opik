package com.comet.opik.infrastructure.db;

import com.comet.opik.domain.mcpoauth.RevokedReason;

public class RevokedReasonMapper extends AbstractEnumColumnMapper<RevokedReason> {
    public RevokedReasonMapper() {
        super(RevokedReason::fromString, "revoked_reason");
    }
}
