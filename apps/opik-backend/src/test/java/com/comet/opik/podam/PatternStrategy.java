package com.comet.opik.podam;

import org.apache.commons.lang3.RandomStringUtils;
import uk.co.jemos.podam.common.AttributeStrategy;

import java.lang.annotation.Annotation;
import java.util.List;

/**
 * <a href="https://mtedone.github.io/podam/validation-api.html">Podam Bean Validation</a> doesn't support @Pattern.
 * Created this pattern strategy as a small workaround for testing.
 * This is a very basic implementation where the generated CharSequence doesn't comply to regex of Pattern.
 */
public class PatternStrategy implements AttributeStrategy<CharSequence> {

    public static final PatternStrategy INSTANCE = new PatternStrategy();

    @Override
    public CharSequence getValue(Class<?> aClass, List<Annotation> list) {
        return RandomStringUtils.randomAlphanumeric(10);
    }
}
