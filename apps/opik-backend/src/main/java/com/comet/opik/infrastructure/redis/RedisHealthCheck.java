package com.comet.opik.infrastructure.redis;

import io.dropwizard.util.Duration;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.NonNull;
import org.redisson.api.RedissonClient;
import org.redisson.api.redisnode.RedisNode;
import org.redisson.api.redisnode.RedisNodes;
import ru.vyarus.dropwizard.guice.module.installer.feature.health.NamedHealthCheck;

import java.util.concurrent.TimeUnit;

@Singleton
public class RedisHealthCheck extends NamedHealthCheck {

    private final RedissonClient redisClient;
    private final long healthCheckTimeoutMillis;

    @Inject
    public RedisHealthCheck(@NonNull RedissonClient redisClient,
            @NonNull @Named("redis_health_check_timeout") Duration healthCheckTimeout) {
        this.redisClient = redisClient;
        this.healthCheckTimeoutMillis = healthCheckTimeout.toMilliseconds();
    }

    @Override
    protected Result check() {
        try {
            if (master().ping(healthCheckTimeoutMillis, TimeUnit.MILLISECONDS)) {
                return Result.healthy();
            }
        } catch (Exception ex) {
            return Result.unhealthy(ex);
        }

        return Result.unhealthy("Redis health check failed");
    }

    /**
     * The node group must match the client's topology or Redisson throws {@code IllegalArgumentException}. Only the
     * master serves traffic, so a lost replica or sentinel keeps the backend ready; in sentinel mode this is the
     * currently discovered master, so the check follows a failover.
     */
    private RedisNode master() {
        return redisClient.getConfig().isSentinelConfig()
                ? redisClient.getRedisNodes(RedisNodes.SENTINEL_MASTER_SLAVE).getMaster()
                : redisClient.getRedisNodes(RedisNodes.SINGLE).getInstance();
    }

    @Override
    public String getName() {
        return "redis";
    }
}
