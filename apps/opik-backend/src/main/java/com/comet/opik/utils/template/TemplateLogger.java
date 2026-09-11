package com.comet.opik.utils.template;

import lombok.extern.slf4j.Slf4j;
import org.stringtemplate.v4.STErrorListener;
import org.stringtemplate.v4.misc.STMessage;

@Slf4j
public class TemplateLogger implements STErrorListener {

    public static final STErrorListener INSTANCE = new TemplateLogger();

    @Override
    public void compileTimeError(STMessage msg) {
        log.error("Template compile time error: {}", msg);
    }

    @Override
    public void runTimeError(STMessage msg) {
        if (isDebugMessage(msg)) {
            log.debug("Template runtime error: {}", msg);
            return;
        }
        log.error("Template runtime error: {}", msg);
    }

    @Override
    public void IOError(STMessage msg) {
        log.error("Template IO error: {}", msg);
    }

    @Override
    public void internalError(STMessage msg) {
        log.error("Template internal error: {}", msg);
    }

    /**
     * Suppressing attribute-not-defined warnings for attributes
     * used only in conditionals (e.g., {@code <if(trace_id)>}).
     * <p>
     * This is a common pattern in SQL query building where parameters are used to conditionally
     * include query fragments without being rendered directly.
     * Commonly rendering is delegated to the bind mechanism.
     */
    private boolean isDebugMessage(STMessage msg) {
        if (msg == null) {
            return true;
        }
        if (msg.error == null) {
            return false;
        }
        return switch (msg.error) {
            case NO_SUCH_PROPERTY, NO_SUCH_ATTRIBUTE -> true;
            default -> false;
        };
    }
}
