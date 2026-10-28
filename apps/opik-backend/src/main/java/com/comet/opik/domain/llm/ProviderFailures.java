package com.comet.opik.domain.llm;

import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;

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
        RedactedThrowable redacted = null;
        for (var link : chain(throwable).reversed()) {
            redacted = new RedactedThrowable(link.getClass().getName(), redacted);
            redacted.setStackTrace(link.getStackTrace());
        }
        return redacted;
    }

    /** The simple class name of the failure's innermost cause, which names what went wrong without any text. */
    public static String rootType(@NonNull Throwable throwable) {
        return chain(throwable).getLast().getClass().getSimpleName();
    }

    /** The failure and its causes, outermost first, up to where the causes loop back, so that logging it can't fail. */
    private static List<Throwable> chain(Throwable throwable) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var chain = new ArrayList<Throwable>();
        for (var link = throwable; link != null && seen.add(link); link = link.getCause()) {
            chain.add(link);
        }
        return chain;
    }

    /** Stands for a throwable of the class it names, without its message. */
    static final class RedactedThrowable extends RuntimeException {
        RedactedThrowable(String className, Throwable cause) {
            super(className, cause, false, true);
        }
    }
}
