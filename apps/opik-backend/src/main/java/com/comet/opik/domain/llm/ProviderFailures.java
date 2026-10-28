package com.comet.opik.domain.llm;

import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/**
 * LLM provider failures as they may be logged. A provider exception's message, and those of its causes, is often what
 * the provider answered, an HTML page or an error body, which is never logged.
 */
@UtilityClass
public class ProviderFailures {

    /**
     * The failure's chain with every message dropped and its classes and stack frames kept, so a log still shows
     * where a call failed. Each link names the class it stands for.
     */
    public static Throwable redacted(@NonNull Throwable throwable) {
        return redacted(throwable, Collections.newSetFromMap(new IdentityHashMap<>()));
    }

    /** The simple class name of the failure's innermost cause, which names what went wrong without any text. */
    public static String rootType(@NonNull Throwable throwable) {
        var root = throwable;
        var seen = Collections.<Throwable>newSetFromMap(new IdentityHashMap<>());
        while (root.getCause() != null && seen.add(root)) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName();
    }

    private static Throwable redacted(Throwable throwable, Set<Throwable> seen) {
        seen.add(throwable);
        var cause = throwable.getCause() != null && !seen.contains(throwable.getCause())
                ? redacted(throwable.getCause(), seen)
                : null;
        var redacted = new RedactedThrowable(throwable.getClass().getName(), cause);
        redacted.setStackTrace(throwable.getStackTrace());
        return redacted;
    }

    /** Stands for a throwable of the class it names, without its message. */
    static final class RedactedThrowable extends RuntimeException {
        RedactedThrowable(String className, Throwable cause) {
            super(className, cause, false, true);
        }
    }
}
