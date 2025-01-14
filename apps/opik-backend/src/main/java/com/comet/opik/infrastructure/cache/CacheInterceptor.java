package com.comet.opik.infrastructure.cache;

import com.comet.opik.infrastructure.CacheConfiguration;
import com.comet.opik.utils.TypeReferenceUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import jakarta.inject.Provider;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.mvel2.MVEL;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Stream;

@Slf4j
@RequiredArgsConstructor
public class CacheInterceptor implements MethodInterceptor {

    private final @NonNull Provider<CacheManager> cacheManager;
    private final @NonNull CacheConfiguration cacheConfiguration;

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method method = invocation.getMethod();

        if (!cacheConfiguration.isEnabled()) {
            return invocation.proceed();
        }

        boolean isReactive = Stream.of(Mono.class, Flux.class)
                .anyMatch(clazz -> clazz.isAssignableFrom(method.getReturnType()));

        var cacheable = method.getAnnotation(Cacheable.class);
        if (cacheable != null) {
            return runCacheAwareAction(invocation, isReactive, cacheable.name(), cacheable.key(),
                    (key, group) -> processCacheableMethod(invocation, isReactive, key, group, cacheable));
        }

        var cachePut = method.getAnnotation(CachePut.class);
        if (cachePut != null) {
            return runCacheAwareAction(invocation, isReactive, cachePut.name(), cachePut.key(),
                    (key, group) -> processCachePutMethod(invocation, isReactive, key, group));
        }

        var cacheEvict = method.getAnnotation(CacheEvict.class);
        if (cacheEvict != null) {
            return runCacheAwareAction(invocation, isReactive, cacheEvict.name(), cacheEvict.key(),
                    (key, group) -> processCacheEvictMethod(invocation, isReactive, key, cacheEvict));
        }

        return invocation.proceed();
    }

    private Object runCacheAwareAction(MethodInvocation invocation, boolean isReactive, String group, String keyAgs,
            BiFunction<String, String, Object> action) throws Throwable {

        String key;

        try {
            key = getKeyName(group, keyAgs, invocation);
        } catch (Exception e) {
            // If there is an error evaluating the key, proceed without caching
            log.error("Error evaluating key expression: {}", keyAgs, e);
            log.warn("Cache will be skipped due to error evaluating key expression");
            return invocation.proceed();
        }

        if (isReactive) {
            return action.apply(key, group);
        }

        return ((Mono<?>) action.apply(key, group)).block();
    }

    private Mono<Object> processCacheEvictMethod(MethodInvocation invocation, boolean isReactive, String key,
            CacheEvict cacheEvict) {
        if (isReactive) {
            try {
                return ((Mono<?>) invocation.proceed())
                        .flatMap(value -> cacheManager.get().evict(key, cacheEvict.keyUsesPatternMatching())
                                .thenReturn(value))
                        .switchIfEmpty(
                                cacheManager.get().evict(key, cacheEvict.keyUsesPatternMatching()).then(Mono.empty()))
                        .map(Function.identity());
            } catch (Throwable e) {
                return Mono.error(e);
            }
        } else {
            try {
                var value = invocation.proceed();
                if (value == null) {
                    return cacheManager.get().evict(key, cacheEvict.keyUsesPatternMatching()).then(Mono.empty());
                }
                return cacheManager.get().evict(key, cacheEvict.keyUsesPatternMatching()).thenReturn(value);
            } catch (Throwable e) {
                return Mono.error(e);
            }
        }
    }

    private Mono<Object> processCachePutMethod(MethodInvocation invocation, boolean isReactive, String key,
            String group) {
        if (isReactive) {
            try {
                return ((Mono<?>) invocation.proceed()).flatMap(value -> cachePut(value, key, group));
            } catch (Throwable e) {
                return Mono.error(e);
            }
        } else {
            try {
                var value = invocation.proceed();
                return cachePut(value, key, group).thenReturn(value);
            } catch (Throwable e) {
                return Mono.error(e);
            }
        }
    }

    private Object processCacheableMethod(MethodInvocation invocation, boolean isReactive, String key,
            String group, Cacheable cacheable) {

        if (isReactive) {

            if (invocation.getMethod().getReturnType().isAssignableFrom(Mono.class)) {
                return handleMono(invocation, key, group, cacheable);
            } else {
                return handleFlux(invocation, key, group, cacheable);
            }
        } else {

            if (cacheable.wrapperType() != Object.class) {
                TypeReference typeReference = TypeReferenceUtils.forTypes(cacheable.wrapperType(),
                        cacheable.returnType());

                return cacheManager.get().get(key, typeReference)
                        .switchIfEmpty(processSyncCacheMiss(invocation, key, group));
            }

            return cacheManager.get().get(key, invocation.getMethod().getReturnType())
                    .map(Object.class::cast)
                    .switchIfEmpty(processSyncCacheMiss(invocation, key, group));
        }
    }

    private Flux<Object> handleFlux(MethodInvocation invocation, String key, String group, Cacheable cacheable) {
        if (cacheable.wrapperType() != Object.class) {
            TypeReference typeReference = TypeReferenceUtils.forTypes(cacheable.wrapperType(),
                    cacheable.returnType());

            TypeReference<List<?>> collectionType = new TypeReference<>() {
                @Override
                public Type getType() {
                    return TypeFactory.defaultInstance().constructCollectionType(List.class,
                            (JavaType) typeReference.getType());
                }
            };

            return getFromCacheOrCallMethod(invocation, key, group, collectionType);
        }

        TypeReference<List<?>> collectionType = new TypeReference<>() {
            @Override
            public Type getType() {
                return TypeFactory.defaultInstance().constructCollectionType(List.class, cacheable.returnType());
            }
        };

        return getFromCacheOrCallMethod(invocation, key, group, collectionType);
    }

    private Flux<Object> getFromCacheOrCallMethod(MethodInvocation invocation, String key, String group,
            TypeReference<List<?>> collectionType) {
        return cacheManager.get()
                .get(key, collectionType)
                .map(Collection.class::cast)
                .flatMapMany(Flux::fromIterable)
                .switchIfEmpty(processFluxCacheMiss(invocation, key, group));
    }

    private Mono<Object> handleMono(MethodInvocation invocation, String key, String group, Cacheable cacheable) {
        if (cacheable.wrapperType() != Object.class) {
            TypeReference typeReference = TypeReferenceUtils.forTypes(cacheable.wrapperType(),
                    cacheable.returnType());

            return cacheManager.get().get(key, typeReference)
                    .switchIfEmpty(processCacheMiss(invocation, key, group));
        }

        return cacheManager.get().get(key, cacheable.returnType())
                .map(Object.class::cast)
                .switchIfEmpty(processCacheMiss(invocation, key, group));
    }

    private Mono<Object> processSyncCacheMiss(MethodInvocation invocation, String key, String group) {
        return Mono.defer(() -> {
            try {
                return Mono.just(invocation.proceed());
            } catch (Throwable e) {
                return Mono.error(e);
            }
        }).flatMap(value -> cachePut(value, key, group));
    }

    private Mono<Object> processCacheMiss(MethodInvocation invocation, String key, String group) {
        return Mono.defer(() -> {
            try {
                return ((Mono<?>) invocation.proceed())
                        .flatMap(value -> cachePut(value, key, group));
            } catch (Throwable e) {
                return Mono.error(e);
            }
        });
    }

    private Flux<Object> processFluxCacheMiss(MethodInvocation invocation, String key, String group) {
        return Flux.defer(() -> {
            try {
                Flux<Object> flux = (Flux<Object>) invocation.proceed();

                var cacheable = flux.cache()
                        .collectList()
                        .flatMap(value -> cachePut(value, key, group));

                return flux
                        .doOnSubscribe(subscription -> Schedulers.boundedElastic().schedule(() -> {
                            cacheable.subscribe(
                                    __ -> log.info("Flux value put in cache"),
                                    e -> log.error("Error putting flux value in cache", e));
                        }));
            } catch (Throwable e) {
                return Flux.error(e);
            }
        });
    }

    private Mono<Object> cachePut(Object value, String key, String group) {
        Duration ttlDuration = cacheConfiguration.getCaches().getOrDefault(group,
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
        Parameter[] parameters = invocation.getMethod().getParameters();
        Object[] args = invocation.getArguments();

        // Populate the context map with parameter names and values
        for (int i = 0; i < invocation.getMethod().getParameterCount(); i++) {
            Object value = args[i];
            params.put("$" + parameters[i].getName(), value != null ? value : ""); // Null safety
        }

        String evaluatedKey = Objects.requireNonNull(MVEL.evalToString(key, params),
                "Key expression cannot return be null");
        if (evaluatedKey.isEmpty() || evaluatedKey.equals("null")) {
            throw new IllegalArgumentException("Key expression cannot return an empty string");
        }
        return "%s:-%s".formatted(name, evaluatedKey);
    }

}
