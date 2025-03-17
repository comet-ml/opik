package com.comet.opik.domain.attachment;

import com.comet.opik.api.attachment.MultipartUploadPart;
import com.comet.opik.infrastructure.S3Config;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
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

@ImplementedBy(FileUploadServiceImpl.class)
public interface FileUploadService {
    CreateMultipartUploadResponse createMultipartUpload(String key, String contentType);

    CompleteMultipartUploadResponse completeMultipartUpload(String key, String uploadId,
            List<MultipartUploadPart> parts);
}

@Slf4j
@Singleton
class FileUploadServiceImpl implements FileUploadService {

    private final S3Client s3Client;
    private final S3Config s3Config;

    @Inject
    public FileUploadServiceImpl(@NonNull @Config("s3Config") S3Config s3Config,
            @NonNull S3Client s3Client) {
        this.s3Config = s3Config;
        this.s3Client = s3Client;
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
