package com.comet.opik.domain.mcpoauth;

import jakarta.annotation.Nullable;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

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
    // Cntrl plus the Unicode line and paragraph separators, which are not control characters but break lines in
    // any log viewer that honours them — the same forgery a \r\n would be.
    private static final Pattern CONTROL_CHARS = Pattern.compile("[\\p{Cntrl}\u2028\u2029]");

    /**
     * Strips control characters, which would otherwise forge lines in the registration log, and caps the
     * value at what the column holds. Truncating rather than rejecting is deliberate: these fields were
     * silently discarded before, so failing an over-long one would break a host that registers fine today.
     */
    public static @Nullable String sanitizeDisplayText(@Nullable String value) {
        String cleaned = stripControlChars(value, " ");
        return cleaned == null ? null : StringUtils.truncate(cleaned, DISPLAY_TEXT_MAX);
    }

    /**
     * Same, for a URL that will be rendered as an {@code <img src>} on the consent page or an {@code <a href>}
     * in the connected-clients UI. Anything that is not a well-formed http(s) URL with a host — {@code javascript:},
     * {@code data:}, a malformed value — is dropped rather than stored, so nothing but a fetchable web URL can
     * reach a sink, whoever renders it.
     */
    public static @Nullable String sanitizeDisplayUri(@Nullable String value) {
        String uri = stripControlChars(value, "");
        if (uri == null) {
            return null;
        }
        // Parsed before the cap, not after: truncating first can cut through a percent-escape and turn a URL
        // the host sent correctly into an unparseable one, which would then be dropped instead of truncated.
        // Parsed, not prefix-matched: "http://[bad" or "http:///no-host" would otherwise be stored as valid.
        // Loopback and private hosts are deliberately allowed — the fetch is the end user's browser rendering
        // an <img>, never this server, and a self-hosted Opik legitimately serves logos from internal hosts.
        try {
            URI parsed = new URI(uri);
            boolean webScheme = parsed.getScheme() != null
                    && StringUtils.equalsAnyIgnoreCase(parsed.getScheme(), "http", "https");
            // Credentials in a display URL would be persisted, rendered into an href and sent on the fetch.
            // RFC 3986 §3.2.1 deprecates the form outright; drop the value rather than leak it.
            if (!webScheme || StringUtils.isBlank(parsed.getHost()) || parsed.getUserInfo() != null) {
                return null;
            }
        } catch (URISyntaxException e) {
            return null;
        }
        return truncateUri(uri);
    }

    /**
     * Replace control characters and trim; blank in is {@code null} out. Trimmed again after the replacement
     * because the separators are not whitespace to {@link String#trim()} — a value of nothing but a U+2028
     * would otherwise come back as the space it was replaced with.
     */
    private static @Nullable String stripControlChars(@Nullable String value, String replacement) {
        String trimmed = StringUtils.trimToNull(value);
        return trimmed == null ? null : StringUtils.trimToNull(CONTROL_CHARS.matcher(trimmed).replaceAll(replacement));
    }

    /**
     * Caps a validated URL at the column width without leaving a half-written percent-escape at the end, which
     * would make the stored value unparseable for whoever renders it.
     */
    private static String truncateUri(String uri) {
        if (uri.length() <= DISPLAY_URI_MAX) {
            return uri;
        }
        int end = DISPLAY_URI_MAX;
        for (int escape = 1; escape <= 2; escape++) {
            if (uri.charAt(end - escape) == '%') {
                end -= escape;
                break;
            }
        }
        return uri.substring(0, end);
    }

    /**
     * Re-applies the display sanitisers to a client read back from the database. Registration has only
     * filtered these fields since this rule landed, so a row written before it may still hold a
     * {@code javascript:} logo or a name with a line break in it, which would otherwise reach the consent page,
     * the connection row and the event untouched; running the same filters on the read path closes that gap
     * without a data migration, and is a no-op for rows written since. {@code name} falls back to the
     * {@code client_id}, as it does at registration, so it can never come back blank.
     */
    public static McpOAuthClient sanitizeDisplayFields(@NonNull McpOAuthClient client) {
        return client.toBuilder()
                .name(StringUtils.defaultIfBlank(sanitizeDisplayText(client.name()), client.id()))
                .softwareId(sanitizeDisplayText(client.softwareId()))
                .softwareVersion(sanitizeDisplayText(client.softwareVersion()))
                .logoUri(sanitizeDisplayUri(client.logoUri()))
                .clientUri(sanitizeDisplayUri(client.clientUri()))
                .build();
    }
}
