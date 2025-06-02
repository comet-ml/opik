package com.comet.opik.podam;

import com.comet.opik.api.validate.InRange;
import org.apache.commons.lang3.RandomUtils;
import uk.co.jemos.podam.common.AttributeStrategy;
import uk.co.jemos.podam.common.BeanValidationStrategy;

import java.lang.annotation.Annotation;
import java.time.Instant;
import java.util.List;

public class InRangeStrategy implements AttributeStrategy<Instant> {

    public static final InRangeStrategy INSTANCE = new InRangeStrategy();

    @Override
    public Instant getValue(Class<?> attrType, List<Annotation> annotations) {
        var inRange = BeanValidationStrategy.findTypeFromList(annotations, InRange.class);
        var minInclusive = Instant.parse(inRange.afterOrEqual());
        var maxExclusive = Instant.parse(inRange.before());
        var now = Instant.now();
        if (now.compareTo(minInclusive) >= 0 && now.isBefore(maxExclusive)) {
            return now;
        }
        return getRandomInstant(minInclusive, maxExclusive);
    }

    public Instant getRandomInstant(String minInclusive, String maxExclusive) {
        return getRandomInstant(Instant.parse(minInclusive), Instant.parse(maxExclusive));
    }

    private Instant getRandomInstant(Instant minInclusive, Instant maxExclusive) {
        var randomEpochMilli = RandomUtils.secure().randomLong(
                minInclusive.toEpochMilli(), maxExclusive.toEpochMilli());
        return Instant.ofEpochMilli(randomEpochMilli);
    }
}
