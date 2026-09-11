package com.comet.opik.infrastructure.net;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;

import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Pre-flight check for outbound calls to user-supplied URLs (SSRF guard). In {@code STRICT} mode
 * (cloud) it requires HTTPS and resolves the hostname before anyone connects, refusing addresses
 * only our own network can reach: loopback, link-local (including the cloud metadata endpoint at
 * 169.254.169.254), RFC 1918 private ranges, IPv6 unique-local, multicast, and unresolvable hosts.
 * In {@code RELAXED} mode (self-hosted default) it is a no-op — internal gateways legitimately
 * live on private ranges there.
 *
 * <p>Resolve-then-decide is the accepted level of protection here: the later connection resolves
 * again, so a DNS-rebinding attacker with a sub-TTL flip could theoretically pass the check. The
 * surfaces this guards are admin-configured (not anonymous input), which keeps that residual risk
 * acceptable; connection-time pinning would require a custom socket layer.
 */
@RequiredArgsConstructor
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

    private final @NonNull Mode mode;

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
            throw new DestinationGuardException("destination '%s' is not a valid URL".formatted(url));
        }
        if (!"https".equalsIgnoreCase(uri.getScheme())) {
            throw new DestinationGuardException(
                    "destination '%s' was refused: only https URLs are allowed".formatted(url));
        }
        String host = uri.getHost();
        if (isBlank(host)) {
            throw new DestinationGuardException("destination '%s' has no valid host".formatted(url));
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException exception) {
            throw new DestinationGuardException(
                    "destination host '%s' could not be resolved".formatted(host));
        }
        for (InetAddress address : addresses) {
            if (isNonPublic(address)) {
                // deliberately not echoing the resolved address: the hostname is the user's own
                // input, the address it maps to inside our network is not theirs to learn
                throw new DestinationGuardException(
                        "destination host '%s' was refused: it resolves to a private or internal address"
                                .formatted(host));
            }
        }
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
