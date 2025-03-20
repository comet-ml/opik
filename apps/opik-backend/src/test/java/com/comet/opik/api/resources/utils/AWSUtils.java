package com.comet.opik.api.resources.utils;

import com.google.inject.AbstractModule;
import lombok.experimental.UtilityClass;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@UtilityClass
public class AWSUtils {
    public static AbstractModule testClients(String minioUrl) {
        return new AbstractModule() {
            @Override
            public void configure() {
                Region region = Region.US_EAST_1;
                var s3Client = S3Client.builder()
                        .region(region)
                        .credentialsProvider(DefaultCredentialsProvider.create())
                        .forcePathStyle(true)
                        .endpointOverride(URI.create(minioUrl))
                        .build();

                S3Configuration s3Configuration = S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build();

                S3Presigner s3Presigner = S3Presigner.builder()
                        .credentialsProvider(DefaultCredentialsProvider.create())
                        .region(region)
                        .serviceConfiguration(s3Configuration)
                        .endpointOverride(URI.create(minioUrl))
                        .build();

                bind(S3Client.class).toInstance(s3Client);
                bind(S3Presigner.class).toInstance(s3Presigner);
            }
        };
    }
}
