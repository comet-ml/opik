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
import org.redisson.api.RedissonReactiveClient;
import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

public class RedisModule extends DropwizardAwareModule<OpikConfiguration> {

    @Provides
    @Singleton
    public RedissonReactiveClient redisClient(@Config("redis") RedisConfig config) {
        return Redisson.create(config.build()).reactive();
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
    public CacheManager cacheManager(RedissonReactiveClient redisClient) {
        return new RedisCacheManager(redisClient);
    }

    @Provides
    @Singleton
    public QueueProducer rqPublisher(RedissonReactiveClient redisClient, IdGenerator idGenerator) {
        return new RqPublisher(redisClient, configuration(), idGenerator);
    }

}
