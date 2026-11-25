package com.comet.opik.api.events;

import jakarta.annotation.Nullable;

public interface OrganizationScopedMessage extends RedisSubscriberMessage {

    @Nullable String organizationId();

    OrganizationScopedMessage withOrganizationId(@Nullable String organizationId);
}
