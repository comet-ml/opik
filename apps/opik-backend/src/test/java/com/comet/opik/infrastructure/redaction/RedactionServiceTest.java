package com.comet.opik.infrastructure.redaction;

import com.comet.opik.infrastructure.RedactionConfig;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Redaction Service")
class RedactionServiceTest {

    private static final List<String> ONE_FIELD = List.of("content");

    private static RedactionService service(boolean enabled, List<String> maskFields) {
        var config = new RedactionConfig();
        config.setEnabled(enabled);
        config.setMaskFields(maskFields);
        return new RedactionService(config);
    }

    @Test
    @DisplayName("the feature follows the switch, since config validation rejects enabled with no fields")
    void theFeatureFollowsTheSwitch() {
        assertThat(service(false, ONE_FIELD).isEnabled()).isFalse();
        assertThat(service(true, ONE_FIELD).isEnabled()).isTrue();

        // Enabled with nothing to mask cannot reach here - RedactionConfig.isConfiguredWhenEnabled fails
        // startup - so isEnabled() does not have to re-check the field set. Asserted so that contract stays
        // visible from this side too.
        assertThat(service(true, ONE_FIELD).masker().isNoOp()).isFalse();
        assertThat(service(false, ONE_FIELD).masker().isNoOp()).isTrue();
    }

    @Test
    @DisplayName("holding the original-data permission is the only thing that exempts a caller")
    void holdingThePermissionIsTheOnlyExemption() {
        var enabled = service(true, ONE_FIELD);

        assertThat(enabled.shouldRedactFor(
                Set.of(WorkspaceUserPermission.ORIGINAL_DATA_VIEW.getValue()))).isFalse();
        assertThat(enabled.shouldRedactFor(Set.of("some_other_permission"))).isTrue();
    }

    @Test
    @DisplayName("an unresolved permission set is masked, which is also every caller when auth is disabled")
    void anUnresolvedPermissionSetIsMasked() {
        // RequestContext.permissions defaults to an empty set and only the permissions lookup fills it, so with
        // authentication.enabled=false this is what every caller looks like: masked, with no way to be granted
        // an exemption. AuthModule logs that at startup, and config.yml says so.
        var enabled = service(true, ONE_FIELD);

        assertThat(enabled.shouldRedactFor(Set.of())).isTrue();
        assertThat(enabled.shouldRedactFor(null)).isTrue();
    }

    @Test
    @DisplayName("nothing is masked while the feature is off, whatever the caller holds")
    void nothingIsMaskedWhileTheFeatureIsOff() {
        assertThat(service(false, ONE_FIELD).shouldRedactFor(Set.of())).isFalse();
        assertThat(service(false, ONE_FIELD).shouldRedactFor(null)).isFalse();
    }
}
