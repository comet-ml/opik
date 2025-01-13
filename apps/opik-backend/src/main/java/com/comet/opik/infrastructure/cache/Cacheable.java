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
