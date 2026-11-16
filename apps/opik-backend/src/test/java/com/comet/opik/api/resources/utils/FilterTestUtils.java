package com.comet.opik.api.resources.utils;

import com.comet.opik.api.filter.Field;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceThreadField;
import com.comet.opik.api.filter.TraceThreadFilter;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;

import java.time.Instant;
import java.util.List;

@UtilityClass
public class FilterTestUtils {

    public static String getValidValue(Field field) {
        return switch (field.getType()) {
            case STRING, STRING_EXACT, STRING_LIST, LIST, DICTIONARY, DICTIONARY_STATE_DB, MAP, ENUM, ENUM_LEGACY,
                    ERROR_CONTAINER, COMMENTS, STRING_STATE_DB, CUSTOM ->
                RandomStringUtils.secure().nextAlphanumeric(10);
            case NUMBER, DURATION, FEEDBACK_SCORES_NUMBER -> String.valueOf(RandomUtils.secure().randomInt(1, 10));
            case DATE_TIME, DATE_TIME_STATE_DB -> Instant.now().toString();
        };
    }

    public static String getKey(Field field) {
        return switch (field.getType()) {
            case STRING, STRING_EXACT, STRING_LIST, NUMBER, DURATION, DATE_TIME, LIST, ENUM, ENUM_LEGACY,
                    ERROR_CONTAINER, COMMENTS, STRING_STATE_DB, DATE_TIME_STATE_DB ->
                null;
            case FEEDBACK_SCORES_NUMBER, CUSTOM -> RandomStringUtils.secure().nextAlphanumeric(10);
            case DICTIONARY, DICTIONARY_STATE_DB, MAP -> "";
        };
    }

    public static String getInvalidValue(Field field) {
        return switch (field.getType()) {
            case STRING, STRING_EXACT, STRING_LIST, DICTIONARY, DICTIONARY_STATE_DB, MAP, CUSTOM, LIST, ENUM,
                    ENUM_LEGACY, ERROR_CONTAINER, COMMENTS, STRING_STATE_DB, DATE_TIME_STATE_DB ->
                " ";
            case NUMBER, DURATION, DATE_TIME, FEEDBACK_SCORES_NUMBER ->
                RandomStringUtils.secure().nextAlphanumeric(10);
        };
    }

    /** A thread list filter matching one thread by id, which takes the thread reads' trace-set prefilter. */
    public static List<TraceThreadFilter> threadIdFilter(@NonNull String threadId) {
        return List.of(TraceThreadFilter.builder()
                .field(TraceThreadField.ID)
                .operator(Operator.EQUAL)
                .value(threadId)
                .build());
    }
}
