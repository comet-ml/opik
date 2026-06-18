package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.resources.oauth.AuthorizeContext;
import com.comet.opik.api.resources.oauth.ClientRegistrationResponse;
import com.comet.opik.api.resources.oauth.ConsentRequest;
import com.comet.opik.api.resources.oauth.ConsentResponse;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.mcpoauth.ClientRegistrationRequest;
import com.comet.opik.domain.mcpoauth.TokenResponse;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Form;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.hc.core5.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Set;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.AUTHORIZE_PATH;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.CODE_CHALLENGE_METHOD_S256;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.CSRF_COOKIE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.GRANT_AUTHORIZATION_CODE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CLIENT_ID;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CODE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_CODE_VERIFIER;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_GRANT_TYPE;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.PARAM_REDIRECT_URI;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.REGISTER_PATH;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.TOKEN_PATH;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives the MCP OAuth authorization-code + PKCE flow through the public endpoints so tests mint real codes and tokens
 * without reaching into the persistence layer. Targets an app running with local auth, where consent resolves to the
 * default workspace.
 */
@RequiredArgsConstructor
public class OAuthResourceClient {

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final String CONTEXT_PATH = AUTHORIZE_PATH + "/context";

    private final ClientSupport client;
    private final String baseURI;
    private final String redirectUri;
    private final String resourceUri;

    public record Minted(String code, TokenResponse tokens) {
    }

    /** Registers a client, walks consent + PKCE, and exchanges the code for the raw code and the token pair. */
    public Minted mintArtifacts() {
        String clientId = registerClient();
        String codeVerifier = RandomStringUtils.secure().nextAlphanumeric(64);
        String code = authorize(clientId, codeVerifier);
        return new Minted(code, exchangeCode(clientId, code, codeVerifier));
    }

    private String registerClient() {
        var request = ClientRegistrationRequest.builder()
                .clientName(RandomStringUtils.secure().nextAlphanumeric(10))
                .redirectUris(Set.of(redirectUri))
                .build();

        try (var response = client.target(baseURI + REGISTER_PATH).request().post(Entity.json(request))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
            return response.readEntity(ClientRegistrationResponse.class).clientId();
        }
    }

    private String authorize(String clientId, String codeVerifier) {
        String csrf = consentContext(clientId).csrfToken();

        var consent = ConsentRequest.builder()
                .clientId(clientId)
                .redirectUri(redirectUri)
                .codeChallenge(codeChallenge(codeVerifier))
                .codeChallengeMethod(CODE_CHALLENGE_METHOD_S256)
                .resource(resourceUri)
                .workspaceName(ProjectService.DEFAULT_WORKSPACE_NAME)
                .csrf(csrf)
                .build();

        try (var response = client.target(baseURI + AUTHORIZE_PATH).request()
                .cookie(CSRF_COOKIE, csrf)
                .post(Entity.json(consent))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return extractCode(response.readEntity(ConsentResponse.class).redirectTo());
        }
    }

    private TokenResponse exchangeCode(String clientId, String code, String codeVerifier) {
        var form = new Form()
                .param(PARAM_GRANT_TYPE, GRANT_AUTHORIZATION_CODE)
                .param(PARAM_CODE, code)
                .param(PARAM_REDIRECT_URI, redirectUri)
                .param(PARAM_CLIENT_ID, clientId)
                .param(PARAM_CODE_VERIFIER, codeVerifier);

        try (var response = client.target(baseURI + TOKEN_PATH).request().post(Entity.form(form))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(TokenResponse.class);
        }
    }

    private AuthorizeContext consentContext(String clientId) {
        try (var response = client.target(baseURI + CONTEXT_PATH)
                .queryParam(PARAM_CLIENT_ID, clientId)
                .queryParam(PARAM_REDIRECT_URI, redirectUri)
                .request()
                .get()) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(AuthorizeContext.class);
        }
    }

    private static String codeChallenge(String codeVerifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(codeVerifier.getBytes(StandardCharsets.US_ASCII));
            return URL_ENCODER.encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String extractCode(String redirectTo) {
        for (String pair : URI.create(redirectTo).getQuery().split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && PARAM_CODE.equals(pair.substring(0, eq))) {
                return pair.substring(eq + 1);
            }
        }
        throw new IllegalStateException("No authorization code in redirect: " + redirectTo);
    }
}
