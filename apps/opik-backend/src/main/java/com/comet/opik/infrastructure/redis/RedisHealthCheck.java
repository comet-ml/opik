package com.comet.opik.infrastructure.redis;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RedissonReactiveClient;
import ru.vyarus.dropwizard.guice.module.installer.feature.health.NamedHealthCheck;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class RedisHealthCheck extends NamedHealthCheck {

    private final @NonNull RedissonReactiveClient redisClient;

    @Override
    protected Result check() {
        try {
            // Try to execute a simple ping command to verify Redis connectivity
            redisClient.getKeys().count().block();
            return Result.healthy();
        } catch (Exception ex) {
            return Result.unhealthy(ex);
        }
    }

    @Override
    public String getName() {
        return "redis";
    }
}
