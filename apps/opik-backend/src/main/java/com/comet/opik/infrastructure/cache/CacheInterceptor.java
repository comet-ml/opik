package com.comet.opik.infrastructure.cache;

import com.comet.opik.infrastructure.CacheConfiguration;
import com.thoughtworks.paranamer.BytecodeReadingParanamer;
import jakarta.inject.Provider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.mvel2.MVEL;
import reactor.core.publisher.Mono;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;

@Slf4j
@RequiredArgsConstructor
public class CacheInterceptor implements MethodInterceptor {

    private static final BytecodeReadingParanamer PARANAMER = new BytecodeReadingParanamer();
    private final Provider<CacheManager> cacheManager;
    private final CacheConfiguration cacheConfiguration;

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();

        if (!cacheConfiguration.isEnabled()) {
            return invocation.proceed();
        }

        boolean isReactive = method.getReturnType().isAssignableFrom(Mono.class);

        var cacheable = method.getAnnotation(Cacheable.class);
        if (cacheable != null) {
            return runCacheAwareAction(invocation, isReactive, cacheable.name(), cacheable.key(),
                    (key, name) -> processCacheableMethod(invocation, isReactive, key, name, cacheable));
        }

        var cachePut = method.getAnnotation(CachePut.class);
        if (cachePut != null) {
            return runCacheAwareAction(invocation, isReactive, cachePut.name(), cachePut.key(),
                    (key, name) -> processCachePutMethod(invocation, isReactive, key, name));
        }

        var cacheEvict = method.getAnnotation(CacheEvict.class);
        if (cacheEvict != null) {
            return runCacheAwareAction(invocation, isReactive, cacheEvict.name(), cacheEvict.key(),
                    (key, name) -> processCacheEvictMethod(invocation, isReactive, key));
        }

        return invocation.proceed();
    }

    private Object runCacheAwareAction(MethodInvocation invocation, boolean isReactive, String name, String keyAgs,
            BiFunction<String, String, Mono<Object>> action) throws Throwable {

        String key;

        try {
            key = getKeyName(name, keyAgs, invocation);
        } catch (Exception e) {
            // If there is an error evaluating the key, proceed without caching
            log.warn("Cache will be skipped due to error evaluating key expression");
            return invocation.proceed();
        }

        if (isReactive) {
            return action.apply(key, name);
        }

        return action.apply(key, name).block();
    }

    private Mono<Object> processCacheEvictMethod(MethodInvocation invocation, boolean isReactive, String key) {
        if (isReactive) {
            try {
                return ((Mono<?>) invocation.proceed())
                        .flatMap(value -> cacheManager.get().evict(key).thenReturn(value))
                        .switchIfEmpty(cacheManager.get().evict(key).then(Mono.empty()))
                        .map(Function.identity());
            } catch (Throwable e) {
                return Mono.error(e);
            }
        } else {
            try {
                var value = invocation.proceed();
                if (value == null) {
                    return cacheManager.get().evict(key).then(Mono.empty());
                }
                return cacheManager.get().evict(key).thenReturn(value);
            } catch (Throwable e) {
                return Mono.error(e);
            }
        }
    }

    private Mono<Object> processCachePutMethod(MethodInvocation invocation, boolean isReactive, String key,
            String name) {
        if (isReactive) {
            try {
                return ((Mono<?>) invocation.proceed()).flatMap(value -> cachePut(value, key, name));
            } catch (Throwable e) {
                return Mono.error(e);
            }
        } else {
            try {
                var value = invocation.proceed();
                return cachePut(value, key, name).thenReturn(value);
            } catch (Throwable e) {
                return Mono.error(e);
            }
        }
    }

    private Mono<Object> processCacheableMethod(MethodInvocation invocation, boolean isReactive, String key,
            String name, Cacheable cacheable) {

        if (isReactive) {
            return cacheManager.get().get(key, cacheable.returnType())
                    .map(Object.class::cast)
                    .switchIfEmpty(processCacheMiss(invocation, key, name));
        } else {
            return cacheManager.get().get(key, invocation.getMethod().getReturnType())
                    .map(Object.class::cast)
                    .switchIfEmpty(processSyncCacheMiss(invocation, key, name));
        }
    }

    private Mono<Object> processSyncCacheMiss(MethodInvocation invocation, String key, String name) {
        return Mono.defer(() -> {
            try {
                return Mono.just(invocation.proceed());
            } catch (Throwable e) {
                return Mono.error(e);
            }
        })
                .flatMap(value -> cachePut(value, key, name));
    }

    private Mono<Object> processCacheMiss(MethodInvocation invocation, String key, String name) {
        return Mono.defer(() -> {
            try {
                return ((Mono<?>) invocation.proceed())
                        .flatMap(value -> cachePut(value, key, name));
            } catch (Throwable e) {
                return Mono.error(e);
            }
        });
    }

    private Mono<Object> cachePut(Object value, String key, String name) {
        Duration ttlDuration = cacheConfiguration.getCaches().getOrDefault(name,
                cacheConfiguration.getDefaultDuration());
        return cacheManager.get().put(key, value, ttlDuration)
                .thenReturn(value)
                .onErrorResume(e -> {
                    log.error("Error putting value in cache", e);
                    return Mono.just(value);
                });
    }

    private String getKeyName(String name, String key, MethodInvocation invocation) {
        Map<String, Object> params = new HashMap<>();

        // Use Paranamer to resolve parameter names
        String[] parameters = PARANAMER.lookupParameterNames(invocation.getMethod());
        Object[] args = invocation.getArguments();

        // Populate the context map with parameter names and values
        for (int i = 0; i < invocation.getMethod().getParameterCount(); i++) {
            Object value = args[i];
            params.put("$" + parameters[i], value != null ? value : ""); // Null safety
        }

        try {
            String evaluatedKey = Objects.requireNonNull(MVEL.evalToString(key, params),
                    "Key expression cannot return be null");
            if (evaluatedKey.isEmpty() || evaluatedKey.equals("null")) {
                throw new IllegalArgumentException("Key expression cannot return an empty string");
            }
            return "%s:-%s".formatted(name, evaluatedKey);
        } catch (Exception e) {
            log.error("Error evaluating key expression: {}", key, e);
            throw new IllegalArgumentException("Error evaluating key expression: " + key);
        }
    }

}
