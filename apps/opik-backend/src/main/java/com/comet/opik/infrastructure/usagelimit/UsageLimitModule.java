package com.comet.opik.infrastructure.usagelimit;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.matcher.Matchers;
import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;

public class UsageLimitModule extends DropwizardAwareModule<OpikConfiguration> {
    @Override
    protected void configure() {
        var requestContext = getProvider(RequestContext.class);

        var quotaService = getProvider(UsageLimitService.class);
        var interceptor = new UsageLimitInterceptor(quotaService, requestContext);

        bindInterceptor(Matchers.any(), Matchers.annotatedWith(UsageLimited.class), interceptor);
    }
}
