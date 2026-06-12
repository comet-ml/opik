package com.comet.opik.api.resources.oauth;

import com.comet.opik.domain.mcpoauth.ClientRegistrationRequest;
import com.comet.opik.domain.mcpoauth.McpOAuthClient;
import com.comet.opik.domain.mcpoauth.OAuthClientService;
import com.comet.opik.domain.mcpoauth.OAuthConstants;
import com.comet.opik.infrastructure.McpOAuthConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.ratelimit.RateLimitService;
import com.comet.opik.utils.JsonUtils;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.glassfish.jersey.test.grizzly.GrizzlyWebTestContainerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.provider.Arguments;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Stream;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.REGISTER_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(DropwizardExtensionsSupport.class)
@DisplayName("OAuth Register Resource Test")
class OAuthRegisterResourceTest {

    private static final Set<String> REDIRECT_URIS = Set.of("http://example.com/cb");
    private static final long REMAINING_TTL_MILLIS = 60_000L;
    private static final long REGISTRATION_RATE_LIMIT = 10L;
    private static final Duration REGISTRATION_RATE_LIMIT_DURATION = Duration.ofMinutes(1);

    private static final OAuthClientService clientService = mock(OAuthClientService.class);
    private static final RateLimitService rateLimitService = mock(RateLimitService.class);

    private static final ResourceExtension EXT;

    static {
        var mcpOAuthConfig = new McpOAuthConfig();
        mcpOAuthConfig.setRegistrationRateLimit(REGISTRATION_RATE_LIMIT);
        mcpOAuthConfig.setRegistrationRateLimitDuration(REGISTRATION_RATE_LIMIT_DURATION);
        var opikConfig = mock(OpikConfiguration.class);
        when(opikConfig.getMcpOAuth()).thenReturn(mcpOAuthConfig);

        EXT = ResourceExtension.builder()
                .setMapper(JsonUtils.getMapper())
                .addResource(new OAuthRegisterResource(clientService, rateLimitService, opikConfig))
                .setTestContainerFactory(new GrizzlyWebTestContainerFactory())
                .build();
    }

    @BeforeEach
    void setUp() {
        reset(clientService, rateLimitService);
    }

    private String randomIp() {
        return "%d.%d.%d.%d".formatted(
                RandomUtils.nextInt(1, 256), RandomUtils.nextInt(0, 256),
                RandomUtils.nextInt(0, 256), RandomUtils.nextInt(1, 256));
    }

    private McpOAuthClient minted(String clientId, String clientName) {
        return McpOAuthClient.builder()
                .id(clientId)
                .name(clientName)
                .redirectUris(REDIRECT_URIS)
                .build();
    }

    private Response register(String clientName, String forwardedFor) {
        var request = EXT.target(REGISTER_PATH).request();
        if (forwardedFor != null) {
            request = request.header(OAuthConstants.X_FORWARDED_FOR_HEADER, forwardedFor);
        }
        return request.post(Entity.json(ClientRegistrationRequest.builder()
                .clientName(clientName)
                .redirectUris(REDIRECT_URIS)
                .build()));
    }

    @Test
    @DisplayName("POST /oauth/register: returns 201 with Location per RFC 7591 §3.2.1 and omits client_id_issued_at")
    void register_success_returns201WithLocationAndBody() {
        when(rateLimitService.isLimitExceeded(anyLong(), anyString(), any())).thenReturn(Mono.just(false));
        String clientId = RandomStringUtils.secure().randomAlphanumeric(8);
        String clientName = RandomStringUtils.randomAlphanumeric(8);
        when(clientService.register(any(ClientRegistrationRequest.class))).thenReturn(minted(clientId, clientName));

        try (Response response = register(clientName, null)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.CREATED.getStatusCode());
            assertThat(response.getLocation().getPath())
                    .isEqualTo(OAuthConstants.CLIENT_CONFIG_PATH_PREFIX + clientId);

            response.bufferEntity();
            assertThat(response.readEntity(String.class)).doesNotContain("client_id_issued_at");

            ClientRegistrationResponse expected = ClientRegistrationResponse.builder()
                    .clientId(clientId)
                    .clientName(clientName)
                    .redirectUris(REDIRECT_URIS)
                    .tokenEndpointAuthMethod(OAuthConstants.AUTH_METHOD_NONE)
                    .grantTypes(OAuthConstants.DEFAULT_GRANT_TYPES)
                    .responseTypes(OAuthConstants.DEFAULT_RESPONSE_TYPES)
                    .build();
            assertThat(response.readEntity(ClientRegistrationResponse.class))
                    .usingRecursiveComparison()
                    .isEqualTo(expected);
        }
    }

    @Test
    @DisplayName("POST /oauth/register: per-IP rate limit exceeded returns 429 with Retry-After and does not register")
    void register_rateLimitExceeded_returns429() {
        when(rateLimitService.isLimitExceeded(anyLong(), anyString(), any())).thenReturn(Mono.just(true));
        when(rateLimitService.getRemainingTTL(anyString(), any())).thenReturn(Mono.just(REMAINING_TTL_MILLIS));

        try (Response response = register(RandomStringUtils.randomAlphanumeric(10), null)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.TOO_MANY_REQUESTS.getStatusCode());
            assertThat(response.getHeaderString(HttpHeaders.RETRY_AFTER))
                    .isEqualTo(String.valueOf(Duration.ofMillis(REMAINING_TTL_MILLIS).toSeconds()));

            OAuthError error = response.readEntity(OAuthError.class);
            assertThat(error.error()).isEqualTo(OAuthConstants.ERROR_TOO_MANY_REQUESTS);
            assertThat(error.errorDescription()).isEqualTo(OAuthConstants.ERROR_DESC_REGISTRATION_RATE_LIMIT);
        }
        verify(clientService, never()).register(any());
    }

    private static Stream<Arguments> invalidRequests() {
        return Stream.of(
                Arguments.of("blank client_name",
                        ClientRegistrationRequest.builder().clientName("  ").redirectUris(REDIRECT_URIS).build()),
                Arguments.of("empty redirect_uris",
                        ClientRegistrationRequest.builder().clientName(RandomStringUtils.randomAlphanumeric(10))
                                .redirectUris(Set.of()).build()),
                Arguments.of("non-absolute redirect_uri",
                        ClientRegistrationRequest.builder().clientName(RandomStringUtils.randomAlphanumeric(10))
                                .redirectUris(Set.of("/%s".formatted(RandomStringUtils.randomAlphanumeric(8))))
                                .build()));
    }

    @Test
    @DisplayName("POST /oauth/register: rate limit bucket keys on last (nginx-appended) X-Forwarded-For hop, not the spoofable first")
    void register_usesLastForwardedForHopAsRateLimitKey() {
        String firstHopIp = randomIp();
        String lastHopIp = randomIp();
        when(rateLimitService.isLimitExceeded(anyLong(), anyString(), any())).thenReturn(Mono.just(true));
        when(rateLimitService.getRemainingTTL(anyString(), any())).thenReturn(Mono.just(REMAINING_TTL_MILLIS));

        try (Response ignored = register(RandomStringUtils.randomAlphanumeric(10), firstHopIp + ", " + lastHopIp)) {
            verify(rateLimitService).isLimitExceeded(anyLong(),
                    eq(OAuthConstants.RATE_LIMIT_BUCKET.formatted(lastHopIp)), any());
        }
    }
}
