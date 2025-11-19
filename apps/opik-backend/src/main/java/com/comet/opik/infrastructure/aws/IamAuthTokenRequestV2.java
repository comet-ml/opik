package com.comet.opik.infrastructure.aws;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.signer.Aws4Signer;
import software.amazon.awssdk.auth.signer.params.Aws4PresignerParams;
import software.amazon.awssdk.http.SdkHttpFullRequest;
import software.amazon.awssdk.http.SdkHttpMethod;
import software.amazon.awssdk.regions.Region;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

@RequiredArgsConstructor
public class IamAuthTokenRequestV2 {

    private static final SdkHttpMethod REQUEST_METHOD = SdkHttpMethod.GET;
    private static final String REQUEST_PROTOCOL = "http://";
    private static final String PARAM_ACTION = "Action";
    private static final String PARAM_USER = "User";
    private static final String ACTION_NAME = "connect";
    private static final String SERVICE_NAME = "elasticache";

    private final @NonNull String userId;
    private final @NonNull String resourceName;
    private final @NonNull String region;
    private final @NonNull Duration tokenExpiryDuration;

    public String toSignedRequestUri(AwsCredentials credentials) {
        SdkHttpFullRequest request = getSignableRequest();

        // Sign the canonical request
        request = sign(request, credentials);

        // Return the signed URI
        return request.getUri().toString().replace(REQUEST_PROTOCOL, "");
    }

    private SdkHttpFullRequest getSignableRequest() {
        return SdkHttpFullRequest.builder()
                .method(REQUEST_METHOD)
                .uri(getRequestUri())
                .appendRawQueryParameter(PARAM_ACTION, ACTION_NAME)
                .appendRawQueryParameter(PARAM_USER, userId)
                .build();
    }

    private URI getRequestUri() {
        return URI.create(String.format("%s%s/", REQUEST_PROTOCOL, resourceName));
    }

    private SdkHttpFullRequest sign(SdkHttpFullRequest request, AwsCredentials credentials) {
        Instant expiryInstant = Instant.now().plus(tokenExpiryDuration);
        Aws4Signer signer = Aws4Signer.create();
        Aws4PresignerParams signerParams = Aws4PresignerParams.builder()
                .signingRegion(Region.of(region))
                .awsCredentials(credentials)
                .signingName(SERVICE_NAME)
                .expirationTime(expiryInstant)
                .build();
        return signer.presign(request, signerParams);
    }
}