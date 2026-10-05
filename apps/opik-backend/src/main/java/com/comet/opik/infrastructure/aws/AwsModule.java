package com.comet.opik.infrastructure.aws;

import com.comet.opik.infrastructure.AlertsEventBridgeConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.S3Config;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.google.inject.Provides;
import jakarta.inject.Singleton;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import software.amazon.awssdk.arns.Arn;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.awscore.retry.AwsRetryStrategy;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.eventbridge.EventBridgeClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;
import java.util.Optional;

public class AwsModule extends DropwizardAwareModule<OpikConfiguration> {

    @Provides
    @Singleton
    public AwsCredentialsProvider credentialsProvider(@Config("s3Config") S3Config config) {
        return DefaultCredentialsProvider.create();
    }

    @Provides
    @Singleton
    public S3Client s3Client(@Config("s3Config") S3Config config, @NonNull AwsCredentialsProvider credentialsProvider) {
        Region region = Region.of(config.getS3Region());

        var builder = S3Client.builder()
                .region(region)
                .credentialsProvider(credentialsProvider);

        if (config.isMinIO()) {
            S3Configuration s3Config = S3Configuration.builder()
                    .checksumValidationEnabled(false)
                    .build();

            builder.forcePathStyle(true)
                    .endpointOverride(URI.create(config.getS3Url()))
                    .serviceConfiguration(s3Config);
        }

        return builder.build();
    }

    @Provides
    @Singleton
    public S3Presigner preSigner(@Config("s3Config") S3Config config,
            @NonNull AwsCredentialsProvider credentialsProvider) {
        Region region = Region.of(config.getS3Region());
        S3Configuration s3Configuration = S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build();

        var builder = S3Presigner.builder()
                .credentialsProvider(credentialsProvider)
                .region(region)
                .serviceConfiguration(s3Configuration);

        if (config.isMinIO()) {
            builder.endpointOverride(URI.create(config.getS3Url()));
        }

        return builder.build();
    }

    @Provides
    @Singleton
    public Optional<EventBridgeClient> eventBridgeClient(@Config("serviceToggles") ServiceTogglesConfig toggles,
            @Config("alertsEventBridge") AlertsEventBridgeConfig config,
            @NonNull AwsCredentialsProvider credentialsProvider) {
        if (!toggles.isEventBridgeAlertsEnabled()) {
            return Optional.empty();
        }

        // Retries are bounded by the publisher, which also retries throttled entries of partial failures
        var builder = EventBridgeClient.builder()
                .credentialsProvider(credentialsProvider)
                .overrideConfiguration(override -> override.retryStrategy(AwsRetryStrategy.doNotRetry()));

        eventBridgeRegion(config).ifPresent(builder::region);

        return Optional.of(builder.build());
    }

    static Optional<Region> eventBridgeRegion(@NonNull AlertsEventBridgeConfig config) {
        if (StringUtils.isNotBlank(config.getRegion())) {
            return Optional.of(Region.of(config.getRegion()));
        }

        return Optional.ofNullable(config.getEventBus())
                .filter(bus -> bus.startsWith("arn:"))
                .flatMap(bus -> Arn.fromString(bus).region())
                .map(Region::of);
    }
}
