package com.comet.opik.infrastructure.redis;

import com.comet.opik.domain.IdGenerator;
import com.comet.opik.infrastructure.DistributedLockConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.RedisConfig;
import com.comet.opik.infrastructure.cache.CacheManager;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.infrastructure.queues.QueueProducer;
import com.comet.opik.infrastructure.ratelimit.RateLimitService;
import com.google.inject.Provides;
import jakarta.inject.Singleton;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.api.RedissonReactiveClient;
import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

public class RedisModule extends DropwizardAwareModule<OpikConfiguration> {

    /**
     * Provides a reactive Redis client that wraps the same underlying Redisson instance.
     * This approach reuses all connectivity resources (connection pool, configuration, etc.)
     * from the synchronous client, avoiding resource duplication.
     */
    @Provides
    @Singleton
    public RedissonReactiveClient redisClient(RedissonClient redisClient) {
        return redisClient.reactive();
    }

    @Provides
    @Singleton
    public RedissonClient redisNonReactiveClient(@Config("redis") RedisConfig config) {
        return Redisson.create(config.build());
    }

    @Provides
    @Singleton
    public LockService lockService(RedissonReactiveClient redisClient,
            @Config("distributedLock") DistributedLockConfig distributedLockConfig) {
        return new RedissonLockService(redisClient, distributedLockConfig);
    }

    @Provides
    @Singleton
    public RateLimitService rateLimitService(RedissonReactiveClient redisClient) {
        return new RedisRateLimitService(redisClient);
    }

    @Provides
    @Singleton
    public CacheManager cacheManager(RedissonReactiveClient redisClient, RedissonClient redisNonReactiveClient) {
        return new RedisCacheManager(redisClient, redisNonReactiveClient);
    }

    @Provides
    @Singleton
    public QueueProducer rqPublisher(RedissonReactiveClient redisClient, IdGenerator idGenerator) {
        return new RqPublisher(redisClient, configuration(), idGenerator);
    }
}
