package com.comet.opik.domain;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.domain.attachment.FileService;
import com.comet.opik.infrastructure.DatasetExportConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CsvDatasetExportProcessorImplTest {

    @Mock
    private DatasetItemDAO datasetItemDao;

    @Mock
    private FileService fileService;

    private DatasetExportConfig exportConfig;

    private CsvDatasetExportProcessorImpl processor;

    private static final UUID DATASET_ID = UUID.randomUUID();
    private static final String WORKSPACE_ID = "test-workspace";

    @BeforeEach
    void setUp() {
        // Use real config with default values
        exportConfig = new DatasetExportConfig();

        // Mock multipart upload methods (lenient because not all tests use them)
        CreateMultipartUploadResponse multipartResponse = CreateMultipartUploadResponse.builder()
                .uploadId("test-upload-id")
                .build();
        lenient().when(fileService.createMultipartUpload(any(), any())).thenReturn(multipartResponse);
        lenient().when(fileService.uploadPart(any(), any(), anyInt(), any())).thenReturn("test-etag");
        lenient().when(fileService.completeMultipartUpload(any(), any(), any())).thenReturn(null);

        processor = new CsvDatasetExportProcessorImpl(datasetItemDao, fileService, exportConfig);
    }

    @Test
    void generateAndUploadCsv_shouldGenerateCsvAndUpload_whenItemsExist() {
        // Given
        Map<String, List<String>> columns = new LinkedHashMap<>();
        columns.put("name", List.of("String"));
        columns.put("age", List.of("Int"));

        List<DatasetItem> items = new ArrayList<>();
        items.add(createItem(UUID.randomUUID(), Map.of(
                "name", JsonUtils.valueToTree("Alice"),
                "age", JsonUtils.valueToTree(30))));
        items.add(createItem(UUID.randomUUID(), Map.of(
                "name", JsonUtils.valueToTree("Bob"),
                "age", JsonUtils.valueToTree(25))));

        when(datasetItemDao.getColumns(DATASET_ID)).thenReturn(Mono.just(columns));
        when(datasetItemDao.getItems(eq(DATASET_ID), anyInt(), any())).thenReturn(Flux.fromIterable(items));

        // When
        Mono<CsvDatasetExportProcessor.CsvExportResult> result = processor.generateAndUploadCsv(DATASET_ID)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID));

        // Then
        StepVerifier.create(result)
                .assertNext(exportResult -> {
                    assertThat(exportResult.filePath())
                            .startsWith("exports/" + WORKSPACE_ID + "/datasets/" + DATASET_ID);
                    assertThat(exportResult.filePath()).endsWith(".csv");
                    assertThat(exportResult.expiresAt()).isAfter(Instant.now());
                })
                .verifyComplete();

        // Verify multipart upload was called
        verify(fileService).createMultipartUpload(any(), eq("text/csv"));

        // Verify at least one part was uploaded
        verify(fileService).uploadPart(any(), eq("test-upload-id"), anyInt(), any());

        // Verify multipart upload was completed
        verify(fileService).completeMultipartUpload(any(), eq("test-upload-id"), any());
    }

    @Test
    void generateAndUploadCsv_shouldHandleEmptyColumns_whenNoItemsExist() {
        // Given
        Map<String, List<String>> columns = new HashMap<>();

        when(datasetItemDao.getColumns(DATASET_ID)).thenReturn(Mono.just(columns));
        when(datasetItemDao.getItems(eq(DATASET_ID), anyInt(), any())).thenReturn(Flux.empty());

        // When
        Mono<CsvDatasetExportProcessor.CsvExportResult> result = processor.generateAndUploadCsv(DATASET_ID)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID));

        // Then
        StepVerifier.create(result)
                .assertNext(exportResult -> {
                    assertThat(exportResult.filePath())
                            .startsWith("exports/" + WORKSPACE_ID + "/datasets/" + DATASET_ID);
                    assertThat(exportResult.filePath()).endsWith(".csv");
                    assertThat(exportResult.expiresAt()).isAfter(Instant.now());
                })
                .verifyComplete();

        // Verify abortMultipartUpload and upload were called for empty dataset
        verify(fileService).abortMultipartUpload(any(), eq("test-upload-id"));
        verify(fileService).upload(any(), any(), eq("text/csv"));
    }

    @Test
    void generateAndUploadCsv_shouldHandleMissingColumns_whenItemHasNullValues() {
        // Given
        Map<String, List<String>> columns = new LinkedHashMap<>(); // Use LinkedHashMap for predictable order
        columns.put("name", List.of("String"));
        columns.put("age", List.of("Int"));
        columns.put("city", List.of("String"));

        List<DatasetItem> items = new ArrayList<>();
        items.add(createItem(UUID.randomUUID(), Map.of(
                "name", JsonUtils.valueToTree("Alice"),
                "age", JsonUtils.valueToTree(30))));
        // Note: "city" column is missing in the item

        when(datasetItemDao.getColumns(DATASET_ID)).thenReturn(Mono.just(columns));
        when(datasetItemDao.getItems(eq(DATASET_ID), anyInt(), any())).thenReturn(Flux.fromIterable(items));

        // When
        Mono<CsvDatasetExportProcessor.CsvExportResult> result = processor.generateAndUploadCsv(DATASET_ID)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID));

        // Then
        StepVerifier.create(result)
                .assertNext(exportResult -> {
                    assertThat(exportResult.filePath()).isNotEmpty();
                })
                .verifyComplete();

        // Verify multipart upload was called
        verify(fileService).createMultipartUpload(any(), eq("text/csv"));
        verify(fileService).completeMultipartUpload(any(), eq("test-upload-id"), any());
    }

    @Test
    void generateAndUploadCsv_shouldHandleComplexJsonValues() {
        // Given
        Map<String, List<String>> columns = new HashMap<>();
        columns.put("name", List.of("String"));
        columns.put("metadata", List.of("Object"));

        JsonNode complexValue = JsonUtils.readTree("{\"key\":\"value\",\"nested\":{\"field\":123}}");

        List<DatasetItem> items = new ArrayList<>();
        items.add(createItem(UUID.randomUUID(), Map.of(
                "name", JsonUtils.valueToTree("Alice"),
                "metadata", complexValue)));

        when(datasetItemDao.getColumns(DATASET_ID)).thenReturn(Mono.just(columns));
        when(datasetItemDao.getItems(eq(DATASET_ID), anyInt(), any())).thenReturn(Flux.fromIterable(items));

        // When
        Mono<CsvDatasetExportProcessor.CsvExportResult> result = processor.generateAndUploadCsv(DATASET_ID)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID));

        // Then
        StepVerifier.create(result)
                .assertNext(exportResult -> {
                    assertThat(exportResult.filePath()).isNotEmpty();
                })
                .verifyComplete();

        // Verify multipart upload was called
        verify(fileService).createMultipartUpload(any(), eq("text/csv"));
        verify(fileService).completeMultipartUpload(any(), eq("test-upload-id"), any());
    }

    @Test
    void generateAndUploadCsv_shouldReturnError_whenColumnsDiscoveryFails() {
        // Given
        when(datasetItemDao.getColumns(DATASET_ID)).thenReturn(Mono.error(new RuntimeException("DB error")));

        // When
        Mono<CsvDatasetExportProcessor.CsvExportResult> result = processor.generateAndUploadCsv(DATASET_ID)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID));

        // Then
        StepVerifier.create(result)
                .expectErrorMatches(throwable -> throwable instanceof RuntimeException &&
                        throwable.getMessage().equals("DB error"))
                .verify();
    }

    @Test
    void generateAndUploadCsv_shouldPreserveColumnInsertionOrder_whenUsingLinkedHashMap() {
        // Given - Use LinkedHashMap to preserve insertion order (as DAO now does)
        Map<String, List<String>> columns = new LinkedHashMap<>();
        columns.put("zebra", List.of("String"));
        columns.put("apple", List.of("String"));
        columns.put("mango", List.of("String"));
        columns.put("banana", List.of("String"));

        List<DatasetItem> items = new ArrayList<>();
        items.add(createItem(UUID.randomUUID(), Map.of(
                "zebra", JsonUtils.valueToTree("z-value"),
                "apple", JsonUtils.valueToTree("a-value"),
                "mango", JsonUtils.valueToTree("m-value"),
                "banana", JsonUtils.valueToTree("b-value"))));

        when(datasetItemDao.getColumns(DATASET_ID)).thenReturn(Mono.just(columns));
        when(datasetItemDao.getItems(eq(DATASET_ID), anyInt(), any())).thenReturn(Flux.fromIterable(items));

        // When
        Mono<CsvDatasetExportProcessor.CsvExportResult> result = processor.generateAndUploadCsv(DATASET_ID)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID));

        // Then
        StepVerifier.create(result)
                .assertNext(exportResult -> {
                    assertThat(exportResult.filePath()).isNotEmpty();
                })
                .verifyComplete();

        // Verify CSV content preserves insertion order from LinkedHashMap
        ArgumentCaptor<byte[]> dataCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(fileService).uploadPart(any(), eq("test-upload-id"), anyInt(), dataCaptor.capture());

        String csvContent = new String(dataCaptor.getValue());
        String[] lines = csvContent.split("\n");

        // First line should be header with columns in insertion order
        assertThat(lines[0].trim()).isEqualTo("zebra,apple,mango,banana");

        // Second line should have values in the same order as headers
        assertThat(lines[1].trim()).isEqualTo("z-value,a-value,m-value,b-value");
    }

    private DatasetItem createItem(UUID id, Map<String, JsonNode> data) {
        return DatasetItem.builder()
                .id(id)
                .data(data)
                .build();
    }
}
