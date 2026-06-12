package com.comet.opik.api.resources.oauth;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.utils.JsonUtils;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.AUTHORIZATION_SERVER_METADATA_PATH;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.AUTHORIZE_PATH;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.AUTH_METHOD_NONE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.CODE_CHALLENGE_METHOD_S256;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.GRANT_AUTHORIZATION_CODE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.GRANT_REFRESH_TOKEN;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.REGISTER_PATH;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.RESPONSE_TYPE_CODE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.REVOKE_PATH;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.TOKEN_PATH;
import static jakarta.ws.rs.core.Response.Status.OK;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(DropwizardExtensionsSupport.class)
@DisplayName("OAuthMetadataResource")
class OAuthMetadataResourceTest {

    private static final String ISSUER = "https://opik.example.com";

    private static final ResourceExtension EXT = ResourceExtension.builder()
            .setMapper(JsonUtils.getMapper())
            .addResource(new OAuthMetadataResource(newConfig()))
            .build();

    private static OpikConfiguration newConfig() {
        var config = new OpikConfiguration();
        config.getMcpOAuth().setBaseUrl(ISSUER + "/");
        return config;
    }

    @Test
    @DisplayName("advertises RFC 8414 metadata derived from the configured issuer")
    void metadataAdvertisesEndpointsDerivedFromIssuer() {
        try (Response response = EXT.target(AUTHORIZATION_SERVER_METADATA_PATH).request().get()) {
            assertThat(response.getStatus()).isEqualTo(OK.getStatusCode());

            var expected = AuthorizationServerMetadata.builder()
                    .issuer(ISSUER)
                    .authorizationEndpoint(ISSUER + AUTHORIZE_PATH)
                    .tokenEndpoint(ISSUER + TOKEN_PATH)
                    .revocationEndpoint(ISSUER + REVOKE_PATH)
                    .registrationEndpoint(ISSUER + REGISTER_PATH)
                    .responseTypesSupported(List.of(RESPONSE_TYPE_CODE))
                    .grantTypesSupported(List.of(GRANT_AUTHORIZATION_CODE, GRANT_REFRESH_TOKEN))
                    .codeChallengeMethodsSupported(List.of(CODE_CHALLENGE_METHOD_S256))
                    .tokenEndpointAuthMethodsSupported(List.of(AUTH_METHOD_NONE))
                    .build();

            assertThat(response.readEntity(AuthorizationServerMetadata.class)).isEqualTo(expected);
        }
    }
}
