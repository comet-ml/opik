package com.comet.opik.domain.attachment;

import com.comet.opik.infrastructure.S3Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("FileService Streaming Upload Tests")
class FileServiceStreamingTest {

    private static final String BUCKET_NAME = "test-bucket";
    private static final String TEST_KEY = "test/file.csv";
    private static final String CONTENT_TYPE = "text/csv";

    @Mock
    private S3Client s3Client;

    @Mock
    private S3Config s3Config;

    @Mock
    private PutObjectResponse putObjectResponse;

    private FileServiceImpl fileService;

    @BeforeEach
    void setUp() {
        lenient().when(s3Config.getS3BucketName()).thenReturn(BUCKET_NAME);
        fileService = new FileServiceImpl(s3Config, s3Client);
    }

    @Test
    @DisplayName("Should successfully upload file using InputStream")
    void shouldUploadFileUsingInputStream() {
        // Given
        String testContent = "test,data,content\n1,2,3\n4,5,6";
        byte[] testBytes = testContent.getBytes(StandardCharsets.UTF_8);
        InputStream inputStream = new ByteArrayInputStream(testBytes);
        long contentLength = testBytes.length;

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(putObjectResponse);

        // When
        PutObjectResponse response = fileService.uploadStream(TEST_KEY, inputStream, contentLength, CONTENT_TYPE);

        // Then
        assertThat(response).isEqualTo(putObjectResponse);

        ArgumentCaptor<PutObjectRequest> requestCaptor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(requestCaptor.capture(), any(RequestBody.class));

        PutObjectRequest capturedRequest = requestCaptor.getValue();
        assertThat(capturedRequest.bucket()).isEqualTo(BUCKET_NAME);
        assertThat(capturedRequest.key()).isEqualTo(TEST_KEY);
        assertThat(capturedRequest.contentType()).isEqualTo(CONTENT_TYPE);
        assertThat(capturedRequest.contentLength()).isEqualTo(contentLength);
    }

    @Test
    @DisplayName("Should upload large file using streaming")
    void shouldUploadLargeFileUsingStreaming() {
        // Given
        byte[] largeContent = new byte[10 * 1024 * 1024]; // 10 MB
        InputStream inputStream = new ByteArrayInputStream(largeContent);
        long contentLength = largeContent.length;

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(putObjectResponse);

        // When
        PutObjectResponse response = fileService.uploadStream(TEST_KEY, inputStream, contentLength, CONTENT_TYPE);

        // Then
        assertThat(response).isEqualTo(putObjectResponse);
        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("Should throw exception when key is null")
    void shouldThrowExceptionWhenKeyIsNull() {
        // Given
        InputStream inputStream = new ByteArrayInputStream("test".getBytes());

        // When & Then
        assertThatThrownBy(() -> fileService.uploadStream(null, inputStream, 4, CONTENT_TYPE))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should throw exception when InputStream is null")
    void shouldThrowExceptionWhenInputStreamIsNull() {
        // When & Then
        assertThatThrownBy(() -> fileService.uploadStream(TEST_KEY, null, 100, CONTENT_TYPE))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should throw exception when contentType is null")
    void shouldThrowExceptionWhenContentTypeIsNull() {
        // Given
        InputStream inputStream = new ByteArrayInputStream("test".getBytes());

        // When & Then
        assertThatThrownBy(() -> fileService.uploadStream(TEST_KEY, inputStream, 4, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should handle S3 upload failure gracefully")
    void shouldHandleS3UploadFailure() {
        // Given
        InputStream inputStream = new ByteArrayInputStream("test".getBytes());
        RuntimeException expectedException = new RuntimeException("S3 upload failed");

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenThrow(expectedException);

        // When & Then
        assertThatThrownBy(() -> fileService.uploadStream(TEST_KEY, inputStream, 4, CONTENT_TYPE))
                .isEqualTo(expectedException);
    }

    @Test
    @DisplayName("Should upload empty file")
    void shouldUploadEmptyFile() {
        // Given
        InputStream inputStream = new ByteArrayInputStream(new byte[0]);
        long contentLength = 0;

        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(putObjectResponse);

        // When
        PutObjectResponse response = fileService.uploadStream(TEST_KEY, inputStream, contentLength, CONTENT_TYPE);

        // Then
        assertThat(response).isEqualTo(putObjectResponse);
        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }
}
