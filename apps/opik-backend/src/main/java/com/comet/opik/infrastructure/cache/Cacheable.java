package com.comet.opik.infrastructure.cache;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to enable caching on method results.
 * <p>
 * <strong>CRITICAL WARNING - Avoid Nested Cache Operations:</strong>
 * </p>
 * <p>
 * Do NOT use this annotation on overloaded methods where one delegates to another.
 * When two overloaded methods both have {@code @Cacheable} and one delegates to the other,
 * it creates <strong>nested cache operations</strong> that cause:
 * </p>
 * <ul>
 *   <li>Nested {@code Mono.block()} calls within the cache interceptor</li>
 *   <li>Reactor threading violations</li>
 *   <li>Redis timeout exceptions ({@code RedisTimeoutException})</li>
 *   <li>Thread pool exhaustion under load</li>
 * </ul>
 * <p>
 * <strong>Correct Pattern:</strong>
 * </p>
 * <pre>{@code
 * // ✅ CORRECT: Only the implementation has @Cacheable
 * public List<Item> findAll(UUID projectId, String workspaceId) {
 *     return findAll(projectId, workspaceId, null);  // Delegates, NO annotation
 * }
 *
 * @Cacheable(name = "items", key = "...")
 * public List<Item> findAll(UUID projectId, String workspaceId, Type type) {
 *     // Implementation with caching
 * }
 * }</pre>
 * <p>
 * <strong>Incorrect Pattern (causes deadlock):</strong>
 * </p>
 * <pre>{@code
 * // ❌ WRONG: Both methods have @Cacheable - creates nested cache operations
 * @Cacheable(name = "items", key = "...")
 * public List<Item> findAll(UUID projectId, String workspaceId) {
 *     return findAll(projectId, workspaceId, null);  // Nested cache!
 * }
 *
 * @Cacheable(name = "items", key = "...")
 * public List<Item> findAll(UUID projectId, String workspaceId, Type type) {
 *     // Implementation
 * }
 * }</pre>
 *
 * @see CachePut
 * @see CacheEvict
 */
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface Cacheable {

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

    /**
     * @return the return type of the method annotated with this annotation.
     * */
    Class<?> returnType();

    /**
     * @return the type of the wrapper class for the return type of the method annotated with this annotation.
     * */
    Class<?> wrapperType() default Object.class;
}
