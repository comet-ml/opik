package com.comet.opik.api.resources.utils;

import lombok.experimental.UtilityClass;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.time.Duration;

@UtilityClass
public class MinIOContainerUtils {
    public static final String MINIO_USER = "miniouser";
    public static final String MINIO_PASSWORD = "miniopassword";
    public static final String MINIO_BUCKET = "test-bucket";

    public static GenericContainer<?> newMinIOContainer() {
        return new GenericContainer<>(
                DockerImageName.parse("docker.io/cloudpirates/image-minio:RELEASE.2025-10-15T17-29-55Z-hardened"))
                .withExposedPorts(9000)
                .withEnv("MINIO_ROOT_USER", MINIO_USER)
                .withEnv("MINIO_ROOT_PASSWORD", MINIO_PASSWORD)
                .withCreateContainerCmdModifier(cmd -> cmd.withEntrypoint("sh", "-c",
                        "mkdir -p /data && exec minio server /data --address :9000"))
                .waitingFor(Wait.forHttp("/minio/health/live").forStatusCode(200)
                        .withStartupTimeout(Duration.ofSeconds(60)))
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
