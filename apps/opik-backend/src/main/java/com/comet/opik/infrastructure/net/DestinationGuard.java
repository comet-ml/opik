package com.comet.opik.infrastructure.net;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Builder;
import lombok.NonNull;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;

import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Pre-flight check for outbound calls to user-supplied URLs (SSRF guard). In {@code STRICT} mode
 * (cloud) it resolves the hostname before anyone connects, refusing addresses only our own network
 * can reach: loopback, link-local (including the cloud metadata endpoint at 169.254.169.254),
 * RFC 1918 private ranges, IPv6 unique-local, multicast, and unresolvable hosts. In
 * {@code RELAXED} mode (self-hosted default) it is a no-op — internal gateways legitimately live on
 * private ranges there.
 *
 * <p>HTTPS enforcement is a separate control, requested per caller via {@link Scheme}. It protects
 * the confidentiality of what we send, not the network we can reach, so callers whose payload
 * carries a credential ({@link Scheme#HTTPS_ONLY}) opt in independently of the address filtering
 * above. A caller passing {@link Scheme#PLAINTEXT_OR_TLS} still gets the full SSRF check.
 *
 * <p>Resolve-then-decide is the accepted level of protection here: the later connection resolves
 * again, so a DNS-rebinding attacker with a sub-TTL flip could theoretically pass the check. The
 * surfaces this guards are admin-configured (not anonymous input), which keeps that residual risk
 * acceptable; connection-time pinning would require a custom socket layer.
 */
@Builder(toBuilder = true)
public class DestinationGuard {

    public enum Mode {
        RELAXED("relaxed"),
        STRICT("strict"),
        ;

        @JsonValue
        private final String value;

        Mode(String value) {
            this.value = value;
        }

        @JsonCreator
        public static Mode fromString(String value) {
            return Arrays.stream(values())
                    .filter(mode -> mode.value.equalsIgnoreCase(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown destination guard mode '%s'".formatted(value)));
        }
    }

    /**
     * Whether the caller also requires TLS. Independent of {@link Mode}: this is about protecting
     * the payload in transit, not about which networks we are willing to reach. Either way the
     * destination must be http or https — the only schemes an HTTP client speaks.
     */
    public enum Scheme {
        /** Accept http as well as https — for payloads where plaintext is the caller's own choice. */
        PLAINTEXT_OR_TLS,
        /** Refuse anything but https — for payloads carrying a credential. */
        HTTPS_ONLY,
    }

    private final @NonNull Mode mode;
    @Builder.Default
    private final @NonNull Scheme scheme = Scheme.HTTPS_ONLY;

    /**
     * @throws DestinationGuardException with a user-facing message when the destination is refused
     */
    public void validate(@NonNull String url) {
        if (mode == Mode.RELAXED) {
            return;
        }

        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException exception) {
            throw new DestinationGuardException("destination is not a valid URL, url '%s'".formatted(url));
        }
        // plaintext is the caller's choice, but the scheme must still be one an HTTP client speaks:
        // file://, gopher:// and friends reach places it never should
        if (!isSchemeAllowed(uri.getScheme())) {
            throw new DestinationGuardException(
                    "destination was refused, only %s URLs are allowed, url '%s'".formatted(allowedSchemes(), url));
        }
        String host = uri.getHost();
        if (isBlank(host)) {
            throw new DestinationGuardException("destination has no valid host, url '%s'".formatted(url));
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException exception) {
            throw new DestinationGuardException(
                    "destination host could not be resolved, host '%s'".formatted(host));
        }
        for (InetAddress address : addresses) {
            if (isNonPublic(address)) {
                // deliberately not echoing the resolved address: the hostname is the user's own
                // input, the address it maps to inside our network is not theirs to learn
                throw new DestinationGuardException(
                        "destination was refused, it resolves to a private or internal address, host '%s'"
                                .formatted(host));
            }
        }
    }

    private boolean isSchemeAllowed(String uriScheme) {
        return "https".equalsIgnoreCase(uriScheme)
                || (scheme == Scheme.PLAINTEXT_OR_TLS && "http".equalsIgnoreCase(uriScheme));
    }

    private String allowedSchemes() {
        return scheme == Scheme.HTTPS_ONLY ? "https" : "http and https";
    }

    private static boolean isNonPublic(InetAddress address) {
        return address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()
                || isUniqueLocalIpv6(address);
    }

    /**
     * fc00::/7 — Java's {@code isSiteLocalAddress} only covers the deprecated fec0::/10 for IPv6.
     */
    private static boolean isUniqueLocalIpv6(InetAddress address) {
        return address instanceof Inet6Address && (address.getAddress()[0] & 0xFE) == 0xFC;
    }
}
