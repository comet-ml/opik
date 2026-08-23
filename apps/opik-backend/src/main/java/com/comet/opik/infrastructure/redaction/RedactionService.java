package com.comet.opik.infrastructure.redaction;

import com.comet.opik.infrastructure.RedactionConfig;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.Set;

/**
 * Decides whether a caller sees stored content or redacted content, and holds the compiled rule set.
 * <p>
 * The decision reads the permissions the platform resolved during authentication and put on the request
 * context. That keeps the authority where it belongs — Opik does not evaluate roles, it consumes the answer —
 * and it costs nothing per request: the permissions arrive on the auth call that already happens, cached with
 * the rest of the credentials. Asking a separate permissions endpoint instead would only work for callers
 * presenting an api key, which silently excludes every browser and OAuth session.
 * <p>
 * Rules are compiled once here; the per-caller part is a set lookup.
 */
@Slf4j
@Singleton
public class RedactionService {

    private final boolean enabled;
    private final RedactionRules rules;

    @Inject
    public RedactionService(@Config RedactionConfig config) {
        this.enabled = config.isEnabled();
        this.rules = config.isEnabled() ? config.compile() : RedactionRules.empty();

        if (enabled) {
            log.info("Read-time redaction enabled with '{}' rule(s)", rules.rules().size());
        }
    }

    /** For contexts that never redact — a test wiring a component directly, for instance. */
    public static RedactionService disabled() {
        return new RedactionService(new RedactionConfig());
    }

    public boolean isEnabled() {
        return enabled && !rules.isEmpty();
    }

    public RedactionRules rules() {
        return rules;
    }

    /**
     * Whether this caller's responses must be redacted. Holding the original-data permission exempts them;
     * everyone else is redacted, so an empty or unresolved permission set means redacted.
     */
    public boolean shouldRedactFor(Set<String> permissions) {
        return isEnabled()
                && (permissions == null
                        || !permissions.contains(WorkspaceUserPermission.TRACE_ORIGINAL_DATA_VIEW.getValue()));
    }
}
