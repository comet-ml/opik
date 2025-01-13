package com.comet.opik.infrastructure.redis;

import com.comet.opik.infrastructure.CacheConfiguration;
import com.comet.opik.infrastructure.DistributedLockConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.RedisConfig;
import com.comet.opik.infrastructure.cache.CacheManager;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.infrastructure.ratelimit.RateLimitService;
import com.google.inject.Provides;
import jakarta.inject.Singleton;
import org.redisson.Redisson;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.jcache.JCacheManager;
import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import javax.cache.Caching;
import javax.cache.spi.CachingProvider;

public class RedisModule extends DropwizardAwareModule<OpikConfiguration> {

    @Provides
    @Singleton
    public RedissonReactiveClient redisClient(@Config("redis") RedisConfig config) {
        return Redisson.create(config.build()).reactive();
    }

    @Provides
    @Singleton
    public Redisson redisClientSync(@Config("redis") RedisConfig config) {
        return (Redisson) Redisson.create(config.build());
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
    public CacheManager cacheManager(@Config CacheConfiguration cacheConfiguration, javax.cache.CacheManager cacheManager) {
        return new RedisCacheManager(cacheManager, cacheConfiguration);
    }


    @Provides
    @Singleton
    public javax.cache.CacheManager redissonConfiguration(Redisson redissonClient) {
        CachingProvider cachingProvider = Caching.getCachingProvider();
        return new JCacheManager(redissonClient, cachingProvider.getDefaultClassLoader(), cachingProvider, cachingProvider.getDefaultProperties(), cachingProvider.getDefaultURI());
    }

}
