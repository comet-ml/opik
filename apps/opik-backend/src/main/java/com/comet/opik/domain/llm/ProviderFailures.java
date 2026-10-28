package com.comet.opik.domain.llm;

import com.google.common.base.Throwables;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

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

    /** The failure and its causes, outermost first; only the failure itself when its causes loop, so that logging it can't fail. */
    private static List<Throwable> chain(Throwable throwable) {
        try {
            return Throwables.getCausalChain(throwable);
        } catch (IllegalArgumentException loop) {
            return List.of(throwable);
        }
    }

    /** Stands for a throwable of the class it names, without its message. */
    static final class RedactedThrowable extends RuntimeException {
        RedactedThrowable(String className, Throwable cause) {
            super(className, cause, false, true);
        }
    }
}
