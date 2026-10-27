package com.comet.opik.infrastructure.net;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import io.netty.handler.ipfilter.IpFilterRuleType;
import io.netty.handler.ipfilter.IpSubnetFilterRule;
import lombok.Builder;
import lombok.NonNull;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Pre-flight check for outbound calls to user-supplied URLs (SSRF guard). In {@code STRICT} mode
 * (cloud) it resolves the hostname before anyone connects, refusing addresses only our own network
 * can reach: loopback, link-local (including the cloud metadata endpoint at 169.254.169.254),
 * RFC 1918 private ranges, carrier-grade NAT (100.64.0.0/10), the other IANA special-purpose IPv4
 * ranges (0.0.0.0/8, 192.0.0.0/24, 198.18.0.0/15, 240.0.0.0/4), IPv6 unique-local, the IPv6 forms that embed an IPv4
 * address (NAT64, IPv4-compatible, 6to4, Teredo), multicast, and unresolvable hosts. IPv4-mapped IPv6 literals resolve to their IPv4 form and are checked as such. In
 * {@code RELAXED} mode (self-hosted default) it is a no-op — internal gateways legitimately live on
 * private ranges there.
 *
 * <p>HTTPS enforcement is a separate control, requested per caller via {@link Scheme}. It protects
 * the confidentiality of what we send, not the network we can reach, so callers whose payload
 * carries a credential ({@link Scheme#HTTPS_ONLY}) opt in independently of the address filtering
 * above. A caller passing {@link Scheme#PLAINTEXT_OR_TLS} still gets the full SSRF check.
 *
 * <p>{@link #validate(String)} resolves, then the caller's client resolves again, so a host whose DNS answer changes
 * between the two lookups can pass it. Clients built on {@link DestinationGuardDns} close that gap: they connect only
 * to the addresses {@link #lookup(String)} returned. Cloud deployments should still restrict egress at the network
 * level.
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
        PLAINTEXT_OR_TLS("plaintext_or_tls"),
        /** Refuse anything but https — for payloads carrying a credential. */
        HTTPS_ONLY("https_only"),
        ;

        @JsonValue
        private final String value;

        Scheme(String value) {
            this.value = value;
        }

        @JsonCreator
        public static Scheme fromString(String value) {
            return Arrays.stream(values())
                    .filter(scheme -> scheme.value.equalsIgnoreCase(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown destination guard scheme '%s'".formatted(value)));
        }
    }

    /**
     * Ranges the JDK's {@link InetAddress} predicates miss: 0.0.0.0/8, carrier-grade NAT (routable inside provider
     * networks), 192.0.0.0/24, 198.18.0.0/15, 240.0.0.0/4, IPv6 unique-local (the JDK's site-local check only covers the
     * deprecated fec0::/10), and the IPv6 forms that embed an IPv4 address a gateway may forward to: NAT64,
     * IPv4-compatible (::/96), 6to4 (2002::/16) and Teredo (2001::/32). The last three are deprecated transition
     * mechanisms, so they are refused whole rather than unwrapped to check the embedded address.
     */
    private static final List<IpSubnetFilterRule> NON_PUBLIC_RANGES = Stream.of(
            "0.0.0.0/8", "100.64.0.0/10", "192.0.0.0/24", "198.18.0.0/15", "240.0.0.0/4", "fc00::/7", "64:ff9b::/96",
            "::/96", "2002::/16", "2001::/32")
            .map(cidr -> new IpSubnetFilterRule(cidr, IpFilterRuleType.REJECT))
            .toList();

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

        lookup(parse(url).getHost());
    }

    /**
     * The host's addresses, refused as {@link #validate(String)} refuses them. For an HTTP client's resolver
     * ({@link DestinationGuardDns}), so the addresses checked are the ones connected to, on every redirect hop.
     *
     * @throws DestinationGuardException with a user-facing message when the host is refused
     */
    public List<InetAddress> lookup(@NonNull String host) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException exception) {
            throw new DestinationGuardException(
                    "destination host could not be resolved, host '%s'".formatted(host), exception);
        }
        if (mode == Mode.STRICT && Arrays.stream(addresses).anyMatch(DestinationGuard::isNonPublic)) {
            // deliberately not echoing the resolved address: the hostname is the user's own
            // input, the address it maps to inside our network is not theirs to learn
            throw new DestinationGuardException(
                    "destination was refused, it resolves to a private or internal address, host '%s'"
                            .formatted(host));
        }
        return List.of(addresses);
    }

    /**
     * The checks that don't depend on DNS: a valid URL with an allowed scheme and a host. For a destination whose host
     * is only known per request, which {@link #validate(String)} must then check before each call.
     *
     * @throws DestinationGuardException with a user-facing message when the destination is refused
     */
    public void validateWithoutResolving(@NonNull String url) {
        if (mode != Mode.RELAXED) {
            parse(url);
        }
    }

    private URI parse(String url) {
        // the messages name the scheme or host at most: the URL can carry userinfo or query-string secrets, and
        // callers log these exceptions (URISyntaxException's message echoes the input, so it isn't kept as the cause)
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException exception) {
            throw new DestinationGuardException("destination is not a valid URL");
        }
        // plaintext is the caller's choice, but the scheme must still be one an HTTP client speaks:
        // file://, gopher:// and friends reach places it never should
        if (!isSchemeAllowed(uri.getScheme())) {
            throw new DestinationGuardException(
                    "destination was refused, only %s URLs are allowed, scheme '%s'".formatted(allowedSchemes(),
                            uri.getScheme()));
        }
        if (isBlank(uri.getHost())) {
            throw new DestinationGuardException("destination has no valid host");
        }
        return uri;
    }

    private boolean isSchemeAllowed(String uriScheme) {
        return "https".equalsIgnoreCase(uriScheme)
                || (scheme == Scheme.PLAINTEXT_OR_TLS && "http".equalsIgnoreCase(uriScheme));
    }

    private String allowedSchemes() {
        return scheme == Scheme.HTTPS_ONLY ? "https" : "http and https";
    }

    private static boolean isNonPublic(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        // the port plays no part in subnet matching
        var socketAddress = new InetSocketAddress(address, 0);
        return NON_PUBLIC_RANGES.stream().anyMatch(range -> range.matches(socketAddress));
    }
}
