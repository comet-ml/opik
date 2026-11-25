package com.comet.opik.infrastructure.redis;

import com.comet.opik.api.events.OrganizationScopedMessage;
import jakarta.annotation.Nullable;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

@UtilityClass
public class OrganizationIdStamper {

    public static Object stamp(@NonNull Object message, @Nullable String organizationId) {
        return message instanceof OrganizationScopedMessage scoped
                && StringUtils.isBlank(scoped.organizationId())
                && StringUtils.isNotBlank(organizationId)
                        ? scoped.withOrganizationId(organizationId)
                        : message;
    }
}
