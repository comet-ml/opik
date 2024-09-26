package com.comet.opik.infrastructure.ratelimit;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.RateLimitConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.matcher.Matchers;
import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;

public class RateLimitModule extends DropwizardAwareModule<OpikConfiguration> {

    @Override
    protected void configure() {

        var rateLimit = getProvider(RateLimitService.class);
        var config = configuration(RateLimitConfig.class);
        var requestContext = getProvider(RequestContext.class);

        var rateLimitInterceptor = new RateLimitInterceptor(requestContext, rateLimit, config);

        bindInterceptor(Matchers.any(), Matchers.annotatedWith(RateLimited.class), rateLimitInterceptor);
    }

}
