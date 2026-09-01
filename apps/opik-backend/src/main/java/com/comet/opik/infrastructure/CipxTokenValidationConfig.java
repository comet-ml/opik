package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Delegated validation of CIPX device tokens. Disabled by default: with {@code enabled=false} the
 * authentication filter never looks at the CIPX token prefix, so self-hosted and local deployments
 * behave exactly as before.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class CipxTokenValidationConfig {

    @Valid @JsonProperty
    private boolean enabled;

    @Valid @JsonProperty
    private String url;

    /** Shared service credential presented to the validator. Not a device token. */
    @Valid @JsonProperty
    @ToString.Exclude
    private String serviceToken;

    /**
     * Without an absolute http/https url the validator would post to a relative target, and without the service
     * credential the validator would reject every call -- or, worse, answer an unauthenticated one and become a
     * token oracle. Either way a misconfigured deployment must not boot.
     */
    @AssertTrue(message = "cipxTokenValidation.url must be an absolute http(s) URL and cipxTokenValidation.serviceToken must be set when cipxTokenValidation.enabled=true")
    public boolean isConfiguredWhenEnabled() {
        if (!enabled) {
            return true;
        }
        if (StringUtils.isBlank(url) || StringUtils.isBlank(serviceToken)) {
            return false;
        }
        try {
            URI uri = new URI(url);
            String scheme = uri.getScheme();
            return uri.isAbsolute() && ("http".equals(scheme) || "https".equals(scheme));
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
