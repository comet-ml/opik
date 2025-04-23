package com.comet.opik.api.resources.utils;

import com.comet.opik.infrastructure.auth.AuthModule;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import lombok.experimental.UtilityClass;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.TrustSelfSignedStrategy;
import org.apache.http.ssl.SSLContextBuilder;

import static com.comet.opik.api.ReactServiceErrorResponse.MISSING_API_KEY;

@UtilityClass
public class TestHttpClientUtils {

    public static final String FAKE_API_KEY_MESSAGE = "User not allowed to access workspace!";
    public static final io.dropwizard.jersey.errors.ErrorMessage UNAUTHORIZED_RESPONSE = new io.dropwizard.jersey.errors.ErrorMessage(
            401, FAKE_API_KEY_MESSAGE);
    public static final String PROJECT_NOT_FOUND_MESSAGE = "Project id: %s not found";
    public static final String PROJECT_NAME_NOT_FOUND_MESSAGE = "Project name: %s not found";
    public static final io.dropwizard.jersey.errors.ErrorMessage NO_API_KEY_RESPONSE = new io.dropwizard.jersey.errors.ErrorMessage(
            401, MISSING_API_KEY);

    public static Client client() {
        try {
            return ClientBuilder.newBuilder()
                    .sslContext(SSLContextBuilder.create()
                            .loadTrustMaterial(new TrustSelfSignedStrategy())
                            .build())
                    .hostnameVerifier(NoopHostnameVerifier.INSTANCE)
                    .build();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static AuthModule testAuthModule() {
        return new AuthModule() {
            @Override
            public Client client() {
                return TestHttpClientUtils.client();
            }
        };
    }
}