package com.comet.opik.infrastructure.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Destination Guard Test")
class DestinationGuardTest {

    private final DestinationGuard strict = new DestinationGuard(DestinationGuard.Mode.STRICT);
    private final DestinationGuard relaxed = new DestinationGuard(DestinationGuard.Mode.RELAXED);

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

    @ParameterizedTest
    @ValueSource(strings = {
            "https://8.8.8.8/token",
            "https://1.1.1.1/oauth/token",
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
}
