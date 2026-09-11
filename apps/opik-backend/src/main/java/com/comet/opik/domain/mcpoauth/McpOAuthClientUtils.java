package com.comet.opik.domain.mcpoauth;

import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;

/**
 * Sanitisers for RFC 7591 client metadata. Dynamic Client Registration is unauthenticated, so every string a
 * client sends is attacker-controlled, and it is persisted, logged and eventually rendered.
 * <p>
 * These deliberately live outside {@link McpOAuthClientMapper}: a {@code String -> String} static method on a
 * MapStruct interface is picked up as an implicit conversion and silently applied to every unrelated String
 * mapping in it — which is how {@code client_id} once got run through the URL filter and came out null.
 */
@UtilityClass
public class McpOAuthClientUtils {

    private static final int DISPLAY_TEXT_MAX = 255;
    private static final int DISPLAY_URI_MAX = 2048;

    /**
     * Strips control characters, which would otherwise forge lines in the registration log, and caps the
     * value at what the column holds. Truncating rather than rejecting is deliberate: these fields were
     * silently discarded before, so failing an over-long one would break a host that registers fine today.
     */
    public static String sanitizeDisplayText(String value) {
        String trimmed = StringUtils.trimToNull(value);
        return trimmed == null ? null : StringUtils.truncate(trimmed.replaceAll("\\p{Cntrl}", " "), DISPLAY_TEXT_MAX);
    }

    /**
     * Same, for a URL that will be rendered as an {@code <img src>} on the consent page or an {@code <a href>}
     * in the connected-clients UI. Anything but http(s) — {@code javascript:}, {@code data:} — is dropped
     * rather than stored, so a script-scheme URL can never reach a sink, whoever renders it.
     */
    public static String sanitizeDisplayUri(String value) {
        String trimmed = StringUtils.trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        String uri = StringUtils.truncate(trimmed.replaceAll("\\p{Cntrl}", ""), DISPLAY_URI_MAX);
        return StringUtils.startsWithAny(uri.toLowerCase(Locale.ROOT), "http://", "https://") ? uri : null;
    }
}
