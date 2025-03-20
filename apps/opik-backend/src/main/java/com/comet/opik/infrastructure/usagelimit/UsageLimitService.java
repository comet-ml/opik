package com.comet.opik.infrastructure.usagelimit;

import com.comet.opik.infrastructure.auth.RequestContext;
import org.apache.commons.collections4.ListUtils;

import java.util.Optional;

public class UsageLimitService {
    public static final String ERR_LIMIT_EXCEEDED = "You have reached the maximum allowed spans for a free account " +
            "(%s). Please contact sales at https://www.comet.com/site/about-us/contact-us/.";

    public Optional<String> isQuotaExceeded(RequestContext requestContext) {
        // no quota means it's either a self-hosted installation or pro plan user
        if (ListUtils.emptyIfNull(requestContext.getQuotas()).isEmpty()) {
            return Optional.empty();
        }

        // check if any quota has been reached
        for (Quota quota : requestContext.getQuotas()) {
            if (quota.used() >= quota.limit()) {
                return Optional.of(getMessage(quota));
            }
        }

        return Optional.empty();
    }

    private String getMessage(Quota quota) {
        return String.format(ERR_LIMIT_EXCEEDED, quota.limit());
    }
}
