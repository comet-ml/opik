package com.comet.opik.infrastructure.cache;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.google.inject.matcher.Matchers;
import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;

public class CacheModule extends DropwizardAwareModule<OpikConfiguration> {

    @Override
    protected void configure() {
        var cacheManagerProvider = getProvider(CacheManager.class);
        var cacheManagerConfig = configuration().getCacheManager();
        var cacheInterceptor = new CacheInterceptor(cacheManagerProvider, cacheManagerConfig);

        bindInterceptor(Matchers.any(), Matchers.annotatedWith(Cacheable.class), cacheInterceptor);
        bindInterceptor(Matchers.any(), Matchers.annotatedWith(CachePut.class), cacheInterceptor);
        bindInterceptor(Matchers.any(), Matchers.annotatedWith(CacheEvict.class), cacheInterceptor);
    }
}
