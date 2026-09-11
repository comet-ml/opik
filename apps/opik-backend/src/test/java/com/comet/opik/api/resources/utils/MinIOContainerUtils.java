package com.comet.opik.api.resources.utils;

import lombok.experimental.UtilityClass;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;

@UtilityClass
public class MinIOContainerUtils {
    public static final String MINIO_USER = "miniouser";
    public static final String MINIO_PASSWORD = "miniopassword";
    public static final String MINIO_BUCKET = "test-bucket";

    public static GenericContainer<?> newMinIOContainer() {
        return new GenericContainer<>(DockerImageName.parse("minio/minio:RELEASE.2025-03-12T18-04-18Z"))
                .withExposedPorts(9000)
                .withEnv("MINIO_ROOT_USER", MINIO_USER)
                .withEnv("MINIO_ROOT_PASSWORD", MINIO_PASSWORD)
                .withCommand("server /data --address :9000")
                .withReuse(true);
    }

    public static void setupBucketAndCredentials(String minioUrl) {
        try (S3Client s3 = S3Client.builder()
                .endpointOverride(URI.create(minioUrl))
                .forcePathStyle(true)
                .region(Region.US_EAST_1)
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(MINIO_USER, MINIO_PASSWORD)))
                .build()) {
            if (!doesBucketExist(s3, MINIO_BUCKET)) {
                s3.createBucket(CreateBucketRequest.builder().bucket(MINIO_BUCKET).build());
            }
        }
        System.setProperty("aws.accessKeyId", MINIO_USER);
        System.setProperty("aws.secretAccessKey", MINIO_PASSWORD);
    }

    private static boolean doesBucketExist(S3Client s3Client, String bucketName) {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }
}
