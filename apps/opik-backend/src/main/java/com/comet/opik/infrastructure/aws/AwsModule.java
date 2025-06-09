package com.comet.opik.infrastructure.aws;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.S3Config;
import com.google.inject.Provides;
import jakarta.inject.Singleton;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

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
}
