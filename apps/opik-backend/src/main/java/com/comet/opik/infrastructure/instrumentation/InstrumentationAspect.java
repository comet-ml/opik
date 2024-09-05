package com.comet.opik.infrastructure.instrumentation;

import com.newrelic.api.agent.NewRelic;
import com.newrelic.api.agent.Trace;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Slf4j
class InstrumentationAspect implements MethodInterceptor {

    @Override
    public Object invoke(MethodInvocation methodInvocation) throws Throwable {
        return instrument(methodInvocation);
    }

    @Trace(dispatcher = true) // Manually trace
    private Object instrument(MethodInvocation joinPoint) throws Throwable {

        Object result = joinPoint.proceed();
        if (result instanceof Mono<?>) {
            return ((Mono<?>) result)
                    .doOnSubscribe(subscription -> instrumentMethod(joinPoint))
                    .doOnError(this::handleError);
        } else if (result instanceof Flux<?>) {
            return ((Flux<?>) result)
                    .doOnSubscribe(subscription -> instrumentMethod(joinPoint))
                    .doOnError(this::handleError);
        } else {
            // If the method is not reactive, handle it normally
            return joinPoint.proceed();
        }
    }

    private void instrumentMethod(MethodInvocation joinPoint) {
        NewRelic.setTransactionName(joinPoint.getMethod().getDeclaringClass().getSimpleName(), joinPoint.getMethod().getName());
    }

    private void handleError(Throwable throwable) {
        NewRelic.noticeError(throwable);
    }

}
