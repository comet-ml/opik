package com.comet.opik.infrastructure.cache;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@Documented
public @interface CacheEvict {

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
     * @return whether the key is a pattern or not. Default is false.
     *
     * @see <a href="https://redis.io/commands/KEYS">Redis KEYS command documentation</a>
     *
     * This is useful when you want to evict multiple keys that match a pattern.
     * */
    boolean keyUsesPatternMatching() default false;
}
