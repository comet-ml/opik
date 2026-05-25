package com.comet.opik.api.filter;

import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Optional;

public final class ErrorInfoFilterKeys {

    public static final String EXCEPTION_TYPE = "exception_type";
    public static final String MESSAGE = "message";
    public static final String TRACEBACK = "traceback";
    public static final String SUPPORTED_KEYS = "exceptionType, message, traceback";

    private ErrorInfoFilterKeys() {
    }

    public static Optional<String> supportedJsonKey(String key) {
        return switch (normalize(key)) {
            case "exceptiontype", EXCEPTION_TYPE -> Optional.of(EXCEPTION_TYPE);
            case MESSAGE -> Optional.of(MESSAGE);
            case TRACEBACK -> Optional.of(TRACEBACK);
            default -> Optional.empty();
        };
    }

    private static String normalize(String key) {
        return StringUtils.trimToEmpty(key).toLowerCase(Locale.ROOT);
    }
}
