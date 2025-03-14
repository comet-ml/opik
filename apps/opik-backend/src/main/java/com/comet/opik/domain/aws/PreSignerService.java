package com.comet.opik.domain.aws;

import com.comet.opik.infrastructure.S3Config;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ImplementedBy(PreSignerServiceImpl.class)
public interface PreSignerService {
    List<String> generatePresignedUrls(String key, Integer totalParts, String uploadId);
}

@Slf4j
@Singleton
class PreSignerServiceImpl implements PreSignerService {

    private final S3Presigner preSigner;
    private final S3Config s3Config;

    @Inject
    public PreSignerServiceImpl(@NonNull @Config("s3Config") S3Config s3Config) {
        this.s3Config = s3Config;

        AwsCredentialsProvider credentialsProvider = AWSUtils.getAWSCredentials(s3Config.getS3Key(),
                s3Config.getS3Secret());
        Region region = Region.of(s3Config.getS3Region());
        S3Configuration s3Configuration = S3Configuration.builder()
                .pathStyleAccessEnabled(true)
                .build();

        var builder = S3Presigner.builder()
                .credentialsProvider(credentialsProvider)
                .region(region)
                .serviceConfiguration(s3Configuration);

        if (s3Config.isMinIO()) {
            builder.endpointOverride(URI.create(s3Config.getS3Url()));
        }

        this.preSigner = builder.build();
    }

    @Override
    public List<String> generatePresignedUrls(@NonNull String key, @NonNull Integer totalParts,
            @NonNull String uploadId) {
        List<String> urls = new ArrayList<>();
        for (int partNumber = 1; partNumber <= totalParts; partNumber++) {
            UploadPartRequest uploadPartRequest = UploadPartRequest.builder()
                    .bucket(s3Config.getS3BucketName())
                    .key(key)
                    .uploadId(uploadId)
                    .partNumber(partNumber)
                    .build();

            UploadPartPresignRequest uploadPartPresignRequest = UploadPartPresignRequest
                    .builder()
                    .signatureDuration(Duration.ofSeconds(s3Config.getPreSignUrlTimeoutSec()))
                    .uploadPartRequest(uploadPartRequest)
                    .build();

            URL presignedUrl = preSigner.presignUploadPart(uploadPartPresignRequest).url();
            urls.add(presignedUrl.toString());
        }

        return urls;
    }
}