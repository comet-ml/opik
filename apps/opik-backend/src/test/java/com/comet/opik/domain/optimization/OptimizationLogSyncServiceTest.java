package com.comet.opik.domain.optimization;

import com.comet.opik.domain.attachment.FileService;
import com.comet.opik.infrastructure.OptimizationLogsConfig;
import com.comet.opik.infrastructure.lock.LockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RKeysReactive;
import org.redisson.api.RListReactive;
import org.redisson.api.RMapReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.codec.StringCodec;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.GZIPInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OptimizationLogSyncService Tests")
class OptimizationLogSyncServiceTest {

    private static final String WORKSPACE_ID = "test-workspace-id";
    private static final UUID OPTIMIZATION_ID = UUID.randomUUID();
    private static final String LOG_KEY = "opik:logs:" + WORKSPACE_ID + ":" + OPTIMIZATION_ID;
    private static final String META_KEY = "opik:logs:" + WORKSPACE_ID + ":" + OPTIMIZATION_ID + ":meta";

    @Mock
    private RedissonReactiveClient redisClient;

    @Mock
    private FileService fileService;

    @Mock
    private LockService lockService;

    @Mock
    private OptimizationLogsConfig config;

    @Mock
    private RListReactive<String> logList;

    @Mock
    private RMapReactive<String, String> metaMap;

    @Mock
    private RKeysReactive keysReactive;

    private OptimizationLogSyncServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new OptimizationLogSyncServiceImpl(redisClient, fileService, lockService, config);

        // Default config - enabled
        lenient().when(config.isEnabled()).thenReturn(true);
        lenient().when(config.getLockTimeout()).thenReturn(java.time.Duration.ofSeconds(30));

        // Default Redis mocks - use doReturn to avoid generic type issues
        lenient().doReturn(logList).when(redisClient).getList(anyString(), any(StringCodec.class));
        lenient().doReturn(metaMap).when(redisClient).getMap(anyString(), any(StringCodec.class));
        lenient().when(redisClient.getKeys()).thenReturn(keysReactive);
    }

    @Nested
    @DisplayName("syncLogsToS3 Tests")
    class SyncLogsToS3Tests {

        @Test
        @DisplayName("Should skip sync when service is disabled")
        void shouldSkipSyncWhenDisabled() {
            // Given
            when(config.isEnabled()).thenReturn(false);

            // When & Then
            StepVerifier.create(service.syncLogsToS3(WORKSPACE_ID, OPTIMIZATION_ID))
                    .verifyComplete();

            verify(redisClient, never()).getList(anyString(), any());
        }

        @Test
        @DisplayName("Should skip sync when no new logs")
        void shouldSkipSyncWhenNoNewLogs() {
            // Given - last_flush_ts >= last_append_ts (uses HMGET via getAll)
            doReturn(Mono.just(Map.of("last_append_ts", "1000", "last_flush_ts", "2000")))
                    .when(metaMap).getAll(any(Set.class));

            // When & Then
            StepVerifier.create(service.syncLogsToS3(WORKSPACE_ID, OPTIMIZATION_ID))
                    .verifyComplete();

            verify(lockService, never()).lockUsingToken(any(), any());
        }

        @Test
        @DisplayName("Should sync logs when there are new logs")
        void shouldSyncLogsWhenNewLogsExist() {
            // Given
            List<String> logs = List.of("Line 1", "Line 2", "Line 3");

            // Uses HMGET via getAll for checking new logs
            doReturn(Mono.just(Map.of("last_append_ts", "2000", "last_flush_ts", "1000")))
                    .when(metaMap).getAll(any(Set.class));
            when(lockService.lockUsingToken(any(), any())).thenReturn(Mono.just(true));
            when(lockService.unlockUsingToken(any())).thenReturn(Mono.empty());
            when(logList.readAll()).thenReturn(Mono.just(logs));
            doReturn(Mono.just("old_value")).when(metaMap).put(eq("last_flush_ts"), anyString());

            // When & Then
            StepVerifier.create(service.syncLogsToS3(WORKSPACE_ID, OPTIMIZATION_ID))
                    .verifyComplete();

            // Verify S3 upload was called with gzipped content
            ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
            ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> contentTypeCaptor = ArgumentCaptor.forClass(String.class);

            verify(fileService).upload(keyCaptor.capture(), contentCaptor.capture(), contentTypeCaptor.capture());

            assertThat(keyCaptor.getValue())
                    .isEqualTo("logs/optimization-studio/" + WORKSPACE_ID + "/" + OPTIMIZATION_ID + ".log.gz");
            assertThat(contentTypeCaptor.getValue()).isEqualTo("application/gzip");

            // Verify content is gzipped and contains the logs
            String decompressed = decompressGzip(contentCaptor.getValue());
            assertThat(decompressed).isEqualTo("Line 1\nLine 2\nLine 3");
        }

        @Test
        @DisplayName("Should skip sync when lock cannot be acquired")
        void shouldSkipSyncWhenLockNotAcquired() {
            // Given - uses HMGET via getAll
            doReturn(Mono.just(Map.of("last_append_ts", "2000", "last_flush_ts", "1000")))
                    .when(metaMap).getAll(any(Set.class));
            when(lockService.lockUsingToken(any(), any())).thenReturn(Mono.just(false));
            // Need to stub readAll because the Mono is assembled (though not subscribed) when lock fails
            when(logList.readAll()).thenReturn(Mono.just(List.of()));

            // When & Then
            StepVerifier.create(service.syncLogsToS3(WORKSPACE_ID, OPTIMIZATION_ID))
                    .verifyComplete();

            // Even though readAll() is called during Mono assembly, the S3 upload should not happen
            verify(fileService, never()).upload(anyString(), any(byte[].class), anyString());
        }
    }

    @Nested
    @DisplayName("finalizeLogsOnCompletion Tests")
    class FinalizeLogsTests {

        @Test
        @DisplayName("Should skip finalization when service is disabled")
        void shouldSkipFinalizationWhenDisabled() {
            // Given
            when(config.isEnabled()).thenReturn(false);

            // When & Then
            StepVerifier.create(service.finalizeLogsOnCompletion(WORKSPACE_ID, OPTIMIZATION_ID))
                    .verifyComplete();

            verify(redisClient, never()).getList(anyString(), any());
        }

        @Test
        @DisplayName("Should finalize logs and reduce TTL")
        void shouldFinalizeLogsAndReduceTTL() {
            // Given
            List<String> logs = List.of("Log line 1", "Log line 2");

            when(lockService.lockUsingToken(any(), any())).thenReturn(Mono.just(true));
            when(lockService.unlockUsingToken(any())).thenReturn(Mono.empty());
            when(logList.readAll()).thenReturn(Mono.just(logs));
            when(keysReactive.expire(anyString(), anyLong(), any())).thenReturn(Mono.just(true));
            doReturn(Mono.just("old_value")).when(metaMap).put(eq("last_flush_ts"), anyString());

            // When & Then
            StepVerifier.create(service.finalizeLogsOnCompletion(WORKSPACE_ID, OPTIMIZATION_ID))
                    .verifyComplete();

            // Verify S3 upload
            verify(fileService).upload(
                    eq("logs/optimization-studio/" + WORKSPACE_ID + "/" + OPTIMIZATION_ID + ".log.gz"),
                    any(byte[].class),
                    eq("application/gzip"));

            // Verify last_flush_ts was updated
            verify(metaMap).put(eq("last_flush_ts"), anyString());

            // Verify TTL reduction on both keys
            verify(keysReactive).expire(eq(LOG_KEY), eq(3600L), any());
            verify(keysReactive).expire(eq(META_KEY), eq(3600L), any());
        }

        @Test
        @DisplayName("Should skip finalization when no logs exist")
        void shouldSkipFinalizationWhenNoLogs() {
            // Given
            when(lockService.lockUsingToken(any(), any())).thenReturn(Mono.just(true));
            when(lockService.unlockUsingToken(any())).thenReturn(Mono.empty());
            when(logList.readAll()).thenReturn(Mono.just(List.of()));

            // When & Then
            StepVerifier.create(service.finalizeLogsOnCompletion(WORKSPACE_ID, OPTIMIZATION_ID))
                    .verifyComplete();

            verify(fileService, never()).upload(anyString(), any(byte[].class), anyString());
        }
    }

    @Nested
    @DisplayName("Gzip Compression Tests")
    class GzipCompressionTests {

        @Test
        @DisplayName("Should compress logs with gzip")
        void shouldCompressLogsWithGzip() {
            // Given
            List<String> logs = List.of(
                    "2025-01-01 10:00:00 [INFO] Starting optimization",
                    "2025-01-01 10:00:01 [INFO] Processing item 1",
                    "2025-01-01 10:00:02 [INFO] Processing item 2",
                    "2025-01-01 10:00:03 [INFO] Optimization complete");

            // Uses HMGET via getAll
            doReturn(Mono.just(Map.of("last_append_ts", "2000", "last_flush_ts", "1000")))
                    .when(metaMap).getAll(any(Set.class));
            when(lockService.lockUsingToken(any(), any())).thenReturn(Mono.just(true));
            when(lockService.unlockUsingToken(any())).thenReturn(Mono.empty());
            when(logList.readAll()).thenReturn(Mono.just(logs));
            doReturn(Mono.just("old_value")).when(metaMap).put(eq("last_flush_ts"), anyString());

            // When
            StepVerifier.create(service.syncLogsToS3(WORKSPACE_ID, OPTIMIZATION_ID))
                    .verifyComplete();

            // Then
            ArgumentCaptor<byte[]> contentCaptor = ArgumentCaptor.forClass(byte[].class);
            verify(fileService).upload(anyString(), contentCaptor.capture(), anyString());

            byte[] compressedContent = contentCaptor.getValue();
            String originalContent = String.join("\n", logs);

            // Verify compression actually reduces size
            assertThat(compressedContent.length).isLessThan(originalContent.getBytes(StandardCharsets.UTF_8).length);

            // Verify content can be decompressed correctly
            String decompressed = decompressGzip(compressedContent);
            assertThat(decompressed).isEqualTo(originalContent);
        }
    }

    /**
     * Helper method to decompress gzipped content for verification.
     */
    private static String decompressGzip(byte[] compressed) {
        try (var bais = new ByteArrayInputStream(compressed);
                var gzip = new GZIPInputStream(bais);
                var baos = new ByteArrayOutputStream()) {
            gzip.transferTo(baos);
            return baos.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to decompress gzip content", e);
        }
    }
}
