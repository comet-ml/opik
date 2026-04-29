package com.comet.opik.api.resources.utils;

import com.comet.opik.api.filter.Field;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;

import java.time.Instant;

@UtilityClass
public class FilterTestUtils {

    public static String getValidValue(Field field) {
        return switch (field.getType()) {
            case STRING, STRING_EXACT, LIST, DICTIONARY, DICTIONARY_STATE_DB, MAP, ENUM, ENUM_LEGACY,
                    ERROR_CONTAINER, STRING_STATE_DB, CUSTOM ->
                RandomStringUtils.secure().nextAlphanumeric(10);
            case NUMBER, DURATION, FEEDBACK_SCORES_NUMBER -> String.valueOf(RandomUtils.secure().randomInt(1, 10));
            case DATE_TIME, DATE_TIME_STATE_DB -> Instant.now().toString();
        };
    }

    public static String getKey(Field field) {
        return switch (field.getType()) {
            case STRING, STRING_EXACT, NUMBER, DURATION, DATE_TIME, LIST, ENUM, ENUM_LEGACY,
                    ERROR_CONTAINER, STRING_STATE_DB, DATE_TIME_STATE_DB ->
                null;
            case FEEDBACK_SCORES_NUMBER, CUSTOM -> RandomStringUtils.secure().nextAlphanumeric(10);
            case DICTIONARY, DICTIONARY_STATE_DB, MAP -> "";
        };
    }

    public static String getInvalidValue(Field field) {
        return switch (field.getType()) {
            case STRING, STRING_EXACT, DICTIONARY, DICTIONARY_STATE_DB, MAP, CUSTOM, LIST, ENUM, ENUM_LEGACY,
                    ERROR_CONTAINER, STRING_STATE_DB, DATE_TIME_STATE_DB ->
                " ";
            case NUMBER, DURATION, DATE_TIME, FEEDBACK_SCORES_NUMBER ->
                RandomStringUtils.secure().nextAlphanumeric(10);
        };
    }
}
