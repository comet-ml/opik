package com.comet.opik.api.resources.oauth;

import com.comet.opik.domain.mcpoauth.ClientRegistrationRequest;
import com.comet.opik.domain.mcpoauth.McpOAuthClient;
import com.comet.opik.domain.mcpoauth.OAuthClientService;
import com.comet.opik.domain.mcpoauth.OAuthConstants;
import com.comet.opik.utils.JsonUtils;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.RandomStringUtils;
import org.glassfish.jersey.test.grizzly.GrizzlyWebTestContainerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.Set;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.REGISTER_PATH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@ExtendWith(DropwizardExtensionsSupport.class)
@DisplayName("OAuth Register Resource Test")
class OAuthRegisterResourceTest {

    private static final Set<String> REDIRECT_URIS = Set.of("http://example.com/cb");

    private static final OAuthClientService clientService = mock(OAuthClientService.class);

    private static final ResourceExtension EXT = ResourceExtension.builder()
            .setMapper(JsonUtils.getMapper())
            .addResource(new OAuthRegisterResource(clientService))
            .setTestContainerFactory(new GrizzlyWebTestContainerFactory())
            .build();

    @BeforeEach
    void setUp() {
        reset(clientService);
    }

    private McpOAuthClient minted(String clientId, String clientName) {
        return McpOAuthClient.builder()
                .id(clientId)
                .name(clientName)
                .redirectUris(REDIRECT_URIS)
                .build();
    }

    private Response register(String clientName) {
        return EXT.target(REGISTER_PATH).request().post(Entity.json(ClientRegistrationRequest.builder()
                .clientName(clientName)
                .redirectUris(REDIRECT_URIS)
                .build()));
    }

    @Test
    @DisplayName("POST /oauth/register: returns 201 with body and omits client_id_issued_at")
    void register_success_returns201WithBody() {
        String clientId = RandomStringUtils.secure().randomAlphanumeric(8);
        String clientName = RandomStringUtils.randomAlphanumeric(8);
        when(clientService.register(any(ClientRegistrationRequest.class))).thenReturn(minted(clientId, clientName));

        try (Response response = register(clientName)) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.CREATED.getStatusCode());

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
}
