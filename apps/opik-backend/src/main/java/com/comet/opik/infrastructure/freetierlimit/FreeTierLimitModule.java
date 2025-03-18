package com.comet.opik.infrastructure.freetierlimit;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.matcher.Matchers;
import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;

public class FreeTierLimitModule extends DropwizardAwareModule<OpikConfiguration> {
    @Override
    protected void configure() {
        var requestContext = getProvider(RequestContext.class);

        var quotaService = getProvider(FreeTierLimitService.class);
        var interceptor = new FreeTierLimitInterceptor(quotaService, requestContext);

        bindInterceptor(Matchers.any(), Matchers.annotatedWith(FreeTierLimited.class), interceptor);
    }
}
