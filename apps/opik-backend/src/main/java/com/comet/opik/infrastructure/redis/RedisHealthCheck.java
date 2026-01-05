package com.comet.opik.infrastructure.redis;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RedissonClient;
import org.redisson.api.redisnode.RedisNodes;
import ru.vyarus.dropwizard.guice.module.installer.feature.health.NamedHealthCheck;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class RedisHealthCheck extends NamedHealthCheck {

    private final @NonNull RedissonClient redisClient;

    @Override
    protected Result check() {
        try {
            if (redisClient.getRedisNodes(RedisNodes.SINGLE).pingAll()) {
                return Result.healthy();
            }
        } catch (Exception ex) {
            return Result.unhealthy(ex);
        }

        return Result.unhealthy("Redis health check failed");
    }

    @Override
    public String getName() {
        return "redis";
    }
}
