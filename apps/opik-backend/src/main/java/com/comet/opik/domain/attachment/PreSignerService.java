package com.comet.opik.domain.attachment;

import com.comet.opik.infrastructure.S3Config;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;

import java.net.URL;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@ImplementedBy(PreSignerServiceImpl.class)
public interface PreSignerService {
    List<String> generatePresignedUrls(String key, Integer totalParts, String uploadId);

    String presignDownloadUrl(String key);

    String presignDownloadUrl(String key, Duration expiresIn);

    long getPresignedUrlExpirationSeconds();
}

@Slf4j
@Singleton
class PreSignerServiceImpl implements PreSignerService {

    private final S3Presigner preSigner;
    private final S3Config s3Config;

    @Inject
    public PreSignerServiceImpl(@NonNull @Config("s3Config") S3Config s3Config,
            @NonNull S3Presigner preSigner) {
        this.s3Config = s3Config;
        this.preSigner = preSigner;
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

    @Override
    public String presignDownloadUrl(String key) {
        return presignDownloadUrl(key, Duration.ofSeconds(s3Config.getPreSignUrlTimeoutSec()));
    }

    @Override
    public String presignDownloadUrl(@NonNull String key, @NonNull Duration expiresIn) {
        // Create a request to get an object
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(s3Config.getS3BucketName())
                .key(key)
                .build();

        // Generate the pre-signed URL
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(expiresIn)
                .getObjectRequest(getObjectRequest)
                .build();

        String url = preSigner.presignGetObject(presignRequest).url().toString();
        log.debug("Generated presigned download URL for key: '{}'", key);
        return url;
    }

    @Override
    public long getPresignedUrlExpirationSeconds() {
        return s3Config.getPreSignUrlTimeoutSec();
    }
}
