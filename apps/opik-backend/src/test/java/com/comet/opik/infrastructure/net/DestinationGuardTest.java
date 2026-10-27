package com.comet.opik.infrastructure.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Destination Guard Test")
class DestinationGuardTest {

    private final DestinationGuard strict = DestinationGuard.builder()
            .mode(DestinationGuard.Mode.STRICT)
            .build();
    private final DestinationGuard relaxed = DestinationGuard.builder()
            .mode(DestinationGuard.Mode.RELAXED)
            .build();

    private final DestinationGuard strictPlaintextAllowed = DestinationGuard.builder()
            .mode(DestinationGuard.Mode.STRICT)
            .scheme(DestinationGuard.Scheme.PLAINTEXT_OR_TLS)
            .build();

    @ParameterizedTest
    @ValueSource(strings = {
            "http://public.example.com/token", // https only
            "https://localhost/token",
            "https://127.0.0.1/token",
            "https://10.1.2.3/token",
            "https://172.16.0.1/token",
            "https://192.168.1.1/token",
            "https://169.254.169.254/latest/meta-data", // cloud metadata endpoint
            "https://[::1]/token",
            "https://[fe80::1]/token",
            "https://[fc00::1]/token", // IPv6 unique-local
            "https://0.0.0.0/token",
            "https://100.64.0.1/token",
            "https://100.127.255.254/token",
            "https://[::ffff:127.0.0.1]/token",
            "https://[::ffff:10.0.0.1]/token",
            "https://[fd12:3456::1]/token",
            "https://0.1.2.3/token",
            "https://192.0.0.1/token",
            "https://198.18.0.1/token",
            "https://198.19.255.254/token",
            "https://240.0.0.1/token",
            "https://255.255.255.255/token",
            "https://[64:ff9b::a00:1]/token", // NAT64 of 10.0.0.1
            "https://[::a00:1]/token", // IPv4-compatible form of 10.0.0.1
            "https://[2002:a00:1::1]/token", // 6to4 of 10.0.0.1
            "https://[2001:0:4136:e378:8000:63bf:f5ff:fffe]/token", // Teredo
            "not a url",
    })
    @DisplayName("strict mode refuses non-https, private, internal and malformed destinations")
    void strictModeRefuses(String url) {
        assertThatThrownBy(() -> strict.validate(url)).isInstanceOf(DestinationGuardException.class);
    }

    @Test
    @DisplayName("strict mode refuses unresolvable hosts")
    void strictModeRefusesUnresolvableHosts() {
        // .invalid is reserved (RFC 2606): guaranteed NXDOMAIN, no DNS flakiness
        assertThatThrownBy(() -> strict.validate("https://token-endpoint.invalid/token"))
                .isInstanceOf(DestinationGuardException.class)
                .hasMessageContaining("could not be resolved");
    }

    @Test
    @DisplayName("the resolver refuses a non-public host and passes an unresolvable one through as not found")
    void resolverTellsRefusedFromUnresolvable() {
        var dns = new DestinationGuardDns(strict);

        assertThatThrownBy(() -> dns.lookup("127.0.0.1")).isInstanceOf(DestinationGuardDns.RefusedHostException.class);
        assertThatThrownBy(() -> dns.lookup("token-endpoint.invalid"))
                .isInstanceOf(UnknownHostException.class)
                .isNotInstanceOf(DestinationGuardDns.RefusedHostException.class);
        assertThatCode(() -> new DestinationGuardDns(relaxed).lookup("127.0.0.1")).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://8.8.8.8/token",
            "https://1.1.1.1/oauth/token",
            "https://100.128.0.1/token",
            "https://198.20.0.1/token",
            "https://192.0.1.1/token",
    })
    @DisplayName("strict mode allows public https destinations")
    void strictModeAllowsPublicHttps(String url) {
        assertThatCode(() -> strict.validate(url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost:9876/token",
            "https://10.1.2.3/token",
            "https://169.254.169.254/latest/meta-data",
    })
    @DisplayName("relaxed mode is a no-op: internal gateways are legitimate self-hosted destinations")
    void relaxedModeAllowsEverything(String url) {
        assertThatCode(() -> relaxed.validate(url)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refusal messages echo the user's hostname, never the resolved address")
    void refusalNeverEchoesTheResolvedAddress() {
        assertThatThrownBy(() -> strict.validate("https://localhost/token"))
                .hasMessageContaining("localhost")
                .hasMessageNotContainingAny("127.0.0.1", "::1");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://8.8.8.8/events",
            "https://8.8.8.8/events",
    })
    @DisplayName("plaintext allowed: accepts http public destinations")
    void plaintextAllowedAcceptsHttp(String url) {
        assertThatCode(() -> strictPlaintextAllowed.validate(url)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://localhost/events",
            "http://10.1.2.3/events",
            "http://169.254.169.254/latest/meta-data",
    })
    @DisplayName("plaintext allowed: still refuses private and internal destinations")
    void plaintextAllowedStillFiltersAddresses(String url) {
        assertThatThrownBy(() -> strictPlaintextAllowed.validate(url)).isInstanceOf(DestinationGuardException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "file:///etc/passwd",
            "gopher://8.8.8.8/events",
    })
    @DisplayName("plaintext allowed means http or https, not any protocol at all")
    void plaintextAllowedRefusesNonHttpSchemes(String url) {
        assertThatThrownBy(() -> strictPlaintextAllowed.validate(url)).isInstanceOf(DestinationGuardException.class);
    }
}
