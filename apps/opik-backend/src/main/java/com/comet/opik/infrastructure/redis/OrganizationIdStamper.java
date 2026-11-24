package com.comet.opik.infrastructure.redis;

import com.comet.opik.api.events.OrganizationScopedMessage;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Create one per publish: it looks the organization up at most once, and only when a message can carry one and
 * doesn't yet.
 */
public class OrganizationIdStamper {

    private final Mono<String> organizationId;

    public OrganizationIdStamper(@NonNull Mono<String> organizationId) {
        this.organizationId = organizationId.cache();
    }

    public Mono<Object> stamp(@NonNull Object message) {
        return message instanceof OrganizationScopedMessage scoped && StringUtils.isBlank(scoped.organizationId())
                ? organizationId.<Object>map(scoped::withOrganizationId).defaultIfEmpty(message)
                : Mono.just(message);
    }

    public Mono<List<Object>> stampAll(@NonNull List<?> messages) {
        return Flux.fromIterable(messages).concatMap(this::stamp).collectList();
    }
}
