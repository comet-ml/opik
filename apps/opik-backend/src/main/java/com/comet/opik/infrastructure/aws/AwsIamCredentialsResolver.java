package com.comet.opik.infrastructure.aws;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import lombok.extern.slf4j.Slf4j;
import org.redisson.config.Credentials;
import org.redisson.config.CredentialsResolver;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;

import static com.comet.opik.infrastructure.RedisConfig.AwsIamAuthConfig;

@Slf4j
public class AwsIamCredentialsResolver implements CredentialsResolver {

    private static final Duration REFRESH_AFTER = Duration.ofMinutes(13);
    private static final Duration EXPIRE_AFTER = Duration.ofMinutes(14);

    private final AwsCredentialsProvider credentialsProvider;
    private final AwsIamAuthConfig awsIamAuth;

    public AwsIamCredentialsResolver(AwsIamAuthConfig awsIamAuth) {
        this(DefaultCredentialsProvider.builder().build(), awsIamAuth);
    }

    public AwsIamCredentialsResolver(AwsCredentialsProvider credentialsProvider, AwsIamAuthConfig awsIamAuth) {
        this.credentialsProvider = credentialsProvider;
        this.awsIamAuth = awsIamAuth;
    }

    // Single-entry cache; key is constant "token"
    private final LoadingCache<String, String> tokenCache = CacheBuilder.newBuilder()
            .refreshAfterWrite(REFRESH_AFTER)
            .expireAfterWrite(EXPIRE_AFTER)
            .maximumSize(1)
            .build(new CacheLoader<>() {

                @Override
                public String load(String key) {
                    return generateToken();
                }

                @Override
                public ListenableFuture<String> reload(String key, String oldValue) {
                    return Futures.submit(() -> generateToken(), Executors.newSingleThreadExecutor());
                }
            });

    @Override
    public CompletionStage<Credentials> resolve(InetSocketAddress address) {
        return CompletableFuture.supplyAsync(() -> tokenCache.getUnchecked("token"))
                .thenApply(token -> new Credentials(awsIamAuth.getAwsUserId(), token));
    }

    private String generateToken() {

        var tokenRequest = new IamAuthTokenRequestV2(
                awsIamAuth.getAwsUserId(),
                awsIamAuth.getAwsResourceName(),
                awsIamAuth.getAwsRegion());

        return tokenRequest.toSignedRequestUri(credentialsProvider.resolveCredentials());
    }

}
