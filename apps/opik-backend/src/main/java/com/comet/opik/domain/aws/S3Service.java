package com.comet.opik.domain.aws;

import com.comet.opik.api.attachment.MultipartUploadPart;
import com.comet.opik.infrastructure.S3Config;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

@ImplementedBy(S3ServiceImpl.class)
public interface S3Service {
    CreateMultipartUploadResponse createMultipartUpload(String key, String contentType);

    CompleteMultipartUploadResponse completeMultipartUpload(String key, String uploadId,
            List<MultipartUploadPart> parts);
}

@Slf4j
@Singleton
class S3ServiceImpl implements S3Service {

    private final S3Client s3Client;
    private final S3Config s3Config;

    @Inject
    public S3ServiceImpl(@NonNull @Config("s3Config") S3Config s3Config) {
        this.s3Config = s3Config;

        AwsCredentialsProvider credentialsProvider = AWSCredentialsUtils.getAWSCredentials(s3Config.getS3Key(),
                s3Config.getS3Secret());
        Region region = Region.of(s3Config.getS3Region());

        this.s3Client = S3Client.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .forcePathStyle(true)
                .build();
    }

    @Override
    public CreateMultipartUploadResponse createMultipartUpload(@NonNull String key, String contentType) {

        CreateMultipartUploadRequest.Builder builder = CreateMultipartUploadRequest
                .builder()
                .bucket(s3Config.getS3BucketName())
                .key(key);

        if (StringUtils.isNotBlank(contentType)) {
            builder = builder.contentType(contentType);
        }
        CreateMultipartUploadRequest request = builder.build();

        return s3Client.createMultipartUpload(request);
    }

    @Override
    public CompleteMultipartUploadResponse completeMultipartUpload(@NonNull String key,
            @NonNull String uploadId,
            @NonNull List<MultipartUploadPart> parts) {

        Collection<CompletedPart> completedParts = parts.stream()
                .map(uploadPart -> CompletedPart.builder()
                        .partNumber(uploadPart.partNumber())
                        .eTag(uploadPart.eTag()).build())
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);

        CompletedMultipartUpload completedMultipartUpload = CompletedMultipartUpload.builder()
                .parts(completedParts).build();
        CompleteMultipartUploadRequest request = CompleteMultipartUploadRequest
                .builder()
                .bucket(s3Config.getS3BucketName())
                .key(key)
                .uploadId(uploadId)
                .multipartUpload(completedMultipartUpload).build();

        return s3Client.completeMultipartUpload(request);
    }
}
