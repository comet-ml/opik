package com.comet.opik.infrastructure.aws;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.S3Config;
import com.google.inject.Provides;
import jakarta.inject.Singleton;
import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ContainerCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;

public class AwsModule extends DropwizardAwareModule<OpikConfiguration> {

    @Provides
    @Singleton
    public AwsCredentialsProvider credentialsProvider(@Config("s3Config") S3Config config) {
        if (config.isEKSPod()) {
            return ContainerCredentialsProvider.builder().build();
        }

        return StaticCredentialsProvider.create(AwsBasicCredentials.create(config.getS3Key(), config.getS3Secret()));
    }
}
