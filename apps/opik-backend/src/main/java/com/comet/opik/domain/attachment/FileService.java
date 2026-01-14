package com.comet.opik.domain.attachment;

import com.comet.opik.api.attachment.MultipartUploadPart;
import com.comet.opik.infrastructure.S3Config;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.StringUtils;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsResponse;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

@ImplementedBy(FileServiceImpl.class)
public interface FileService {
    CreateMultipartUploadResponse createMultipartUpload(String key, String contentType);

    String uploadPart(String key, String uploadId, int partNumber, byte[] data);

    CompleteMultipartUploadResponse completeMultipartUpload(String key, String uploadId,
            List<MultipartUploadPart> parts);

    void abortMultipartUpload(String key, String uploadId);

    PutObjectResponse upload(String key, byte[] data, String contentType);

    PutObjectResponse uploadStream(String key, InputStream inputStream, long contentLength, String contentType);

    InputStream download(String key);

    void deleteObjects(Set<String> keys);
}

@Slf4j
@Singleton
class FileServiceImpl implements FileService {

    private final S3Client s3Client;
    private final S3Config s3Config;

    private static final int DELETE_BATCH_SIZE = 1000;

    @Inject
    public FileServiceImpl(@NonNull @Config("s3Config") S3Config s3Config,
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
    @WithSpan
    public String uploadPart(@NonNull String key, @NonNull String uploadId, int partNumber, @NonNull byte[] data) {
        log.debug("Uploading part {} for key: '{}', size: {} bytes", partNumber, key, data.length);

        UploadPartRequest uploadPartRequest = UploadPartRequest.builder()
                .bucket(s3Config.getS3BucketName())
                .key(key)
                .uploadId(uploadId)
                .partNumber(partNumber)
                .build();

        UploadPartResponse response = s3Client.uploadPart(uploadPartRequest, RequestBody.fromBytes(data));
        String eTag = response.eTag();

        log.debug("Successfully uploaded part {} for key: '{}', eTag: '{}'", partNumber, key, eTag);
        return eTag;
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

    @Override
    @WithSpan
    public void abortMultipartUpload(@NonNull String key, @NonNull String uploadId) {
        log.warn("Aborting multipart upload for key: '{}', uploadId: '{}'", key, uploadId);

        try {
            AbortMultipartUploadRequest request = AbortMultipartUploadRequest.builder()
                    .bucket(s3Config.getS3BucketName())
                    .key(key)
                    .uploadId(uploadId)
                    .build();

            s3Client.abortMultipartUpload(request);
            log.info("Successfully aborted multipart upload for key: '{}', uploadId: '{}'", key, uploadId);
        } catch (Exception exception) {
            log.error("Failed to abort multipart upload for key: '{}', uploadId: '{}'", key, uploadId, exception);
            // Don't rethrow - abort is best-effort cleanup
        }
    }

    @Override
    @WithSpan
    public PutObjectResponse upload(@NonNull String key, @NonNull byte[] data, @NonNull String contentType) {
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(s3Config.getS3BucketName())
                .key(key)
                .contentType(contentType)
                .build();

        return s3Client.putObject(putRequest, RequestBody.fromBytes(data));
    }

    @Override
    @WithSpan
    public PutObjectResponse uploadStream(@NonNull String key, @NonNull InputStream inputStream, long contentLength,
            @NonNull String contentType) {
        log.info("Uploading file with streaming for key: '{}'", key);

        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(s3Config.getS3BucketName())
                .key(key)
                .contentType(contentType)
                .contentLength(contentLength)
                .build();

        try {
            PutObjectResponse response = s3Client.putObject(putRequest,
                    RequestBody.fromInputStream(inputStream, contentLength));
            log.info("Successfully uploaded file for key: '{}'", key);
            return response;
        } catch (Exception exception) {
            log.error("Failed to upload file for key: '{}'", key, exception);
            throw exception;
        }
    }

    @Override
    public InputStream download(@NonNull String key) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(s3Config.getS3BucketName())
                .key(key)
                .build();

        try {
            return s3Client.getObject(getObjectRequest);
        } catch (NoSuchKeyException e) {
            log.warn("File is not found for download, key '{}'", key);
            throw new NotFoundException("File not found for key: " + key, e);
        }
    }

    @Override
    @WithSpan
    public void deleteObjects(@NonNull Set<String> keys) {
        List<ObjectIdentifier> objectsToDelete = keys.stream()
                .map(key -> ObjectIdentifier.builder().key(key).build())
                .toList();

        for (List<ObjectIdentifier> partition : ListUtils.partition(objectsToDelete, DELETE_BATCH_SIZE)) {
            DeleteObjectsRequest deleteRequest = DeleteObjectsRequest.builder()
                    .bucket(s3Config.getS3BucketName())
                    .delete(Delete.builder().objects(partition).build())
                    .build();

            // Execute delete operation
            DeleteObjectsResponse response = s3Client.deleteObjects(deleteRequest);
            response.errors()
                    .forEach(error -> log.error("Failed to delete: {}, error: {}", error.key(), error.message()));
        }
    }
}
