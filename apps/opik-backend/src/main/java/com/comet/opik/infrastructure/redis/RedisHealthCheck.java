package com.comet.opik.infrastructure.redis;

import io.dropwizard.util.Duration;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.NonNull;
import org.redisson.api.RedissonClient;
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

    /**
     * {@code RedisNodes.SINGLE} must match the topology set in {@link com.comet.opik.infrastructure.RedisConfig#build()},
     * which uses {@code Config.useSingleServer()}. If the topology changes (e.g. to cluster),
     * this call will throw {@code IllegalArgumentException} and the health check will report unhealthy.
     */
    @Override
    protected Result check() {
        try {
            if (redisClient.getRedisNodes(RedisNodes.SINGLE).pingAll(healthCheckTimeoutMillis, TimeUnit.MILLISECONDS)) {
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
