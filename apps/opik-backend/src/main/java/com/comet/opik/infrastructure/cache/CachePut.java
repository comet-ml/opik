package com.comet.opik.infrastructure.cache;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to update the cache with method results.
 * <p>
 * <strong>CRITICAL WARNING - Avoid Nested Cache Operations:</strong>
 * </p>
 * <p>
 * Do NOT use this annotation on overloaded methods where one delegates to another.
 * When two overloaded methods both have {@code @CachePut} and one delegates to the other,
 * it creates <strong>nested cache operations</strong> that cause:
 * </p>
 * <ul>
 *   <li>Nested {@code Mono.block()} calls within the cache interceptor</li>
 *   <li>Reactor threading violations</li>
 *   <li>Redis timeout exceptions ({@code RedisTimeoutException})</li>
 *   <li>Thread pool exhaustion under load</li>
 * </ul>
 * <p>
 * <strong>Safe Usage:</strong> Only annotate the actual implementation method, not delegating methods.
 * </p>
 *
 * @see Cacheable
 * @see CacheEvict
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface CachePut {

    /**
     * @return the name of the cache group.
     * */
    String name();

    /**
     * key is a SpEL expression implemented using MVEL. Please refer to the <a href="http://mvel.documentnode.com/">MVEL documentation for more information</a>.
     *
     * @return SpEL expression evaluated to generate the cache key.
     * */
    String key();

}
