package com.comet.opik.infrastructure.usagelimit;

import com.comet.opik.infrastructure.UsageLimitConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.Inject;
import lombok.NonNull;
import org.apache.commons.collections4.ListUtils;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.Optional;

public class UsageLimitService {
    private final UsageLimitConfig usageLimitConfig;

    @Inject
    public UsageLimitService(@NonNull @Config("usageLimit") UsageLimitConfig usageLimitConfig) {
        this.usageLimitConfig = usageLimitConfig;
    }

    public Optional<String> isQuotaExceeded(RequestContext requestContext) {
        // no quota means it's either a self-hosted installation or pro plan user
        if (ListUtils.emptyIfNull(requestContext.getQuotas()).isEmpty()) {
            return Optional.empty();
        }

        // check if any quota has been reached
        for (Quota quota : requestContext.getQuotas()) {
            if (quota.used() >= quota.limit()) {
                return Optional.of(getMessage());
            }
        }

        return Optional.empty();
    }

    private String getMessage() {
        return usageLimitConfig.getErrorMessage();
    }
}
