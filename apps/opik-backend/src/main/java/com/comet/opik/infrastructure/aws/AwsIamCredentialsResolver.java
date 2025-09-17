package com.comet.opik.infrastructure.aws;

import com.comet.opik.infrastructure.RedisConfig.AwsIamAuthConfig;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.config.Credentials;
import org.redisson.config.CredentialsResolver;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
public class AwsIamCredentialsResolver implements CredentialsResolver {

    private final @NonNull AwsCredentialsProvider credentialsProvider;
    private final @NonNull AwsIamAuthConfig awsIamAuth;

    // Shared single-threaded executor for cache refreshes
    private final ExecutorService cacheRefreshExecutor;
    private final LoadingCache<String, String> tokenCache;

    public AwsIamCredentialsResolver(@NonNull AwsIamAuthConfig awsIamAuth) {
        this(DefaultCredentialsProvider.builder().build(), awsIamAuth);
    }

    public AwsIamCredentialsResolver(@NonNull AwsCredentialsProvider credentialsProvider,
            @NonNull AwsIamAuthConfig awsIamAuth) {
        this.awsIamAuth = awsIamAuth;
        this.credentialsProvider = credentialsProvider;

        cacheRefreshExecutor = Executors.newSingleThreadExecutor();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            cacheRefreshExecutor.shutdown();
            try {
                if (!cacheRefreshExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    cacheRefreshExecutor.shutdownNow();
                }
            } catch (InterruptedException ie) {
                log.warn("Interrupted while waiting for cache refresh executor to terminate", ie);
                Thread.currentThread().interrupt();
                cacheRefreshExecutor.shutdownNow();
            }
        }));
        tokenCache = buildTokenCache();
    }

    // Single-entry cache; key is constant "token"
    private LoadingCache<String, String> buildTokenCache() {
        return CacheBuilder.newBuilder()
                .refreshAfterWrite(this.awsIamAuth.getTokenCacheRefreshAfter().toJavaDuration())
                .expireAfterWrite(this.awsIamAuth.getTokenCacheExpireAfter().toJavaDuration())
                .maximumSize(1)
                .build(new CacheLoader<>() {

                    @Override
                    public String load(String key) {
                        return generateToken();
                    }

                    @Override
                    public ListenableFuture<String> reload(String key, String oldValue) {
                        return Futures.submit(() -> generateToken(), cacheRefreshExecutor);
                    }
                });
    }

    @Override
    public CompletionStage<Credentials> resolve(InetSocketAddress address) {
        return CompletableFuture.supplyAsync(() -> tokenCache.getUnchecked("token"))
                .thenApply(token -> new Credentials(this.awsIamAuth.getAwsUserId(), token));
    }

    private String generateToken() {

        var tokenRequest = new IamAuthTokenRequestV2(
                this.awsIamAuth.getAwsUserId(),
                this.awsIamAuth.getAwsResourceName(),
                this.awsIamAuth.getAwsRegion(),
                this.awsIamAuth.getTokenExpiryDuration().toJavaDuration());

        return tokenRequest.toSignedRequestUri(this.credentialsProvider.resolveCredentials());
    }

}
