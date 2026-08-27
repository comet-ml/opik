package com.comet.opik.infrastructure.redaction;

import com.comet.opik.infrastructure.RedactionConfig;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.Set;

/**
 * Decides whether a caller sees stored content or masked content, and holds the compiled field set.
 * <p>
 * The decision reads the permissions the platform resolved during authentication and put on the request context.
 * That keeps the authority where it belongs — Opik does not evaluate roles, it consumes the answer — and it costs
 * nothing extra per request for api-key callers, whose credentials are already cached. Asking a separate
 * permissions endpoint instead would only work for callers presenting an api key, which silently excludes every
 * browser and OAuth session.
 */
@Slf4j
@Singleton
public class RedactionService {

    private final boolean enabled;
    private final FieldMasker masker;

    @Inject
    public RedactionService(@Config RedactionConfig config) {
        this.enabled = config.isEnabled();
        this.masker = config.isEnabled() ? config.compile() : FieldMasker.noOp();

        if (enabled) {
            log.info("Read-time redaction enabled, masking '{}' field name(s)", masker.maskedFields().size());
        }
    }

    /** For contexts that never mask — a test wiring a component directly, for instance. */
    public static RedactionService disabled() {
        return new RedactionService(new RedactionConfig());
    }

    /**
     * Config validation rejects enabled-with-no-fields at startup, so enabled here means fields are configured.
     * Nothing silently degrades to a pass-through.
     */
    public boolean isEnabled() {
        return enabled;
    }

    public FieldMasker masker() {
        return masker;
    }

    /**
     * Whether this caller's responses must be masked. Holding the original-data permission exempts them;
     * everyone else is masked, so an empty or unresolved permission set means masked.
     */
    public boolean shouldRedactFor(Set<String> permissions) {
        return isEnabled()
                && (permissions == null
                        || !permissions.contains(WorkspaceUserPermission.ORIGINAL_DATA_VIEW.getValue()));
    }
}
