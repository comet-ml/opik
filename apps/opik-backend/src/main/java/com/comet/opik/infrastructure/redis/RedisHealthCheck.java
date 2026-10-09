package com.comet.opik.infrastructure.redis;

import io.dropwizard.util.Duration;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.NonNull;
import org.redisson.api.RedissonClient;
import org.redisson.api.redisnode.BaseRedisNodes;
import org.redisson.api.redisnode.RedisNodes;
import ru.vyarus.dropwizard.guice.module.installer.feature.health.NamedHealthCheck;

import java.util.concurrent.TimeUnit;

@Singleton
public class RedisHealthCheck extends NamedHealthCheck {

    private final RedissonClient redisClient;
    private final long healthCheckTimeoutMillis;
    private final boolean sentinelEnabled;

    @Inject
    public RedisHealthCheck(@NonNull RedissonClient redisClient,
            @NonNull @Named("redis_health_check_timeout") Duration healthCheckTimeout,
            @Named("redis_sentinel_enabled") boolean sentinelEnabled) {
        this.redisClient = redisClient;
        this.healthCheckTimeoutMillis = healthCheckTimeout.toMilliseconds();
        this.sentinelEnabled = sentinelEnabled;
    }

    @Override
    protected Result check() {
        try {
            if (redisNodes().pingAll(healthCheckTimeoutMillis, TimeUnit.MILLISECONDS)) {
                return Result.healthy();
            }
        } catch (Exception ex) {
            return Result.unhealthy(ex);
        }

        return Result.unhealthy("Redis health check failed");
    }

    /**
     * The requested node group must match the topology set in
     * {@link com.comet.opik.infrastructure.RedisConfig#build()}, otherwise Redisson rejects the call with
     * {@code IllegalArgumentException} and the health check reports unhealthy. In sentinel mode the group covers the
     * master and its replicas as currently discovered through the sentinels, so the check follows a failover.
     */
    private BaseRedisNodes redisNodes() {
        return sentinelEnabled
                ? redisClient.getRedisNodes(RedisNodes.SENTINEL_MASTER_SLAVE)
                : redisClient.getRedisNodes(RedisNodes.SINGLE);
    }

    @Override
    public String getName() {
        return "redis";
    }
}
