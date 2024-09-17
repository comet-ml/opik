package com.comet.opik.infrastructure.ratelimit;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.RateLimitConfig;
import com.google.inject.matcher.Matchers;
import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;

public class RateLimitModule extends DropwizardAwareModule<OpikConfiguration> {

    @Override
    protected void configure() {

        var rateLimit = configuration(RateLimitService.class);
        var config = configuration(RateLimitConfig.class);
        var rateLimitInterceptor = new RateLimitInterceptor(rateLimit, config);

        bindInterceptor(Matchers.any(), Matchers.annotatedWith(RateLimited.class), rateLimitInterceptor);
    }

}
