package com.comet.opik.infrastructure.redis;

import com.comet.opik.api.events.RedisSubscriberMessage;
import com.comet.opik.infrastructure.auth.OrganizationResolver;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;

/**
 * Stamps the current organization onto stream messages that don't carry one. Create one per publish: the
 * organization is looked up at most once per instance, and only when a message needs it.
 */
public class OrganizationIdStamper {

    private final Mono<String> organizationId;

    public OrganizationIdStamper(@NonNull OrganizationResolver organizationResolver) {
        this.organizationId = organizationResolver.organizationId().cache();
    }

    public Mono<Object> stamp(@NonNull Object message) {
        return message instanceof RedisSubscriberMessage scoped && StringUtils.isBlank(scoped.organizationId())
                ? organizationId.<Object>map(scoped::withOrganizationId).defaultIfEmpty(message)
                : Mono.just(message);
    }
}
