package com.comet.opik.infrastructure.usagelimit;

import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Provider;
import jakarta.ws.rs.ClientErrorException;
import lombok.RequiredArgsConstructor;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.hc.core5.http.HttpStatus;

import java.lang.reflect.Method;

@RequiredArgsConstructor
public class UsageLimitInterceptor implements MethodInterceptor {
    private final Provider<UsageLimitService> usageLimitServiceProvider;
    private final Provider<RequestContext> requestContextProvider;

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        // get the method being invoked
        Method method = invocation.getMethod();

        // check if the method is annotated with @UsageLimited
        if (!method.isAnnotationPresent(UsageLimited.class)) {
            return invocation.proceed();
        }

        // check if free tier limit is exceeded and if so throw an exception
        usageLimitServiceProvider.get().isQuotaExceeded(requestContextProvider.get())
                .ifPresent(msg -> {
                    throw new ClientErrorException(msg, HttpStatus.SC_PAYMENT_REQUIRED);
                });

        return invocation.proceed();
    }
}
