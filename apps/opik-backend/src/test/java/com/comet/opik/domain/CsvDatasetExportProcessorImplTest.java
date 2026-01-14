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
        Map<String, List<String>> columns = new HashMap<>();
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
        Mono<String> result = processor.generateAndUploadCsv(DATASET_ID)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID));

        // Then
        StepVerifier.create(result)
                .assertNext(filePath -> {
                    assertThat(filePath).startsWith("exports/" + WORKSPACE_ID + "/datasets/" + DATASET_ID);
                    assertThat(filePath).endsWith(".csv");
                })
                .verifyComplete();

        // Verify multipart upload was called
        verify(fileService).createMultipartUpload(any(), eq("text/csv"));

        // Verify at least one part was uploaded
        ArgumentCaptor<byte[]> dataCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(fileService).uploadPart(any(), eq("test-upload-id"), anyInt(), dataCaptor.capture());

        // Verify CSV content in uploaded part
        String csvContent = new String(dataCaptor.getValue());
        assertThat(csvContent).contains("name,age"); // Headers
        assertThat(csvContent).contains("Alice,30"); // First row
        assertThat(csvContent).contains("Bob,25"); // Second row

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
        Mono<String> result = processor.generateAndUploadCsv(DATASET_ID)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID));

        // Then
        StepVerifier.create(result)
                .assertNext(filePath -> {
                    assertThat(filePath).startsWith("exports/" + WORKSPACE_ID + "/datasets/" + DATASET_ID);
                })
                .verifyComplete();

        // Verify multipart upload was created then aborted (empty dataset special case)
        verify(fileService).createMultipartUpload(any(), eq("text/csv"));
        verify(fileService).abortMultipartUpload(any(), eq("test-upload-id"));

        // Verify fallback to regular upload for empty file
        ArgumentCaptor<byte[]> dataCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(fileService).upload(any(), dataCaptor.capture(), eq("text/csv"));

        String csvContent = new String(dataCaptor.getValue());
        // CSV with no columns produces just a newline or empty string
        assertThat(csvContent).hasSizeLessThan(5); // Allow for newline characters
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
        Mono<String> result = processor.generateAndUploadCsv(DATASET_ID)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID));

        // Then
        StepVerifier.create(result)
                .assertNext(filePath -> assertThat(filePath).isNotEmpty())
                .verifyComplete();

        // Verify multipart upload was called
        verify(fileService).createMultipartUpload(any(), eq("text/csv"));

        // Verify CSV content
        ArgumentCaptor<byte[]> dataCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(fileService).uploadPart(any(), eq("test-upload-id"), anyInt(), dataCaptor.capture());

        String csvContent = new String(dataCaptor.getValue());
        // Verify headers are present (order from database/LinkedHashMap)
        assertThat(csvContent).containsPattern("name.*age.*city");
        // Verify data row with empty city value
        assertThat(csvContent).contains("Alice");
        assertThat(csvContent).contains("30");

        // Verify multipart upload was completed
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
        Mono<String> result = processor.generateAndUploadCsv(DATASET_ID)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID));

        // Then
        StepVerifier.create(result)
                .assertNext(filePath -> assertThat(filePath).isNotEmpty())
                .verifyComplete();

        // Verify multipart upload was called
        verify(fileService).createMultipartUpload(any(), eq("text/csv"));

        // Verify CSV content
        ArgumentCaptor<byte[]> dataCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(fileService).uploadPart(any(), eq("test-upload-id"), anyInt(), dataCaptor.capture());

        String csvContent = new String(dataCaptor.getValue());
        assertThat(csvContent).contains("Alice");
        assertThat(csvContent).contains("key"); // Complex JSON is serialized as string

        // Verify multipart upload was completed
        verify(fileService).completeMultipartUpload(any(), eq("test-upload-id"), any());
    }

    @Test
    void generateAndUploadCsv_shouldReturnError_whenColumnsDiscoveryFails() {
        // Given
        when(datasetItemDao.getColumns(DATASET_ID)).thenReturn(Mono.error(new RuntimeException("DB error")));

        // When
        Mono<String> result = processor.generateAndUploadCsv(DATASET_ID)
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID));

        // Then
        StepVerifier.create(result)
                .expectErrorMatches(throwable -> throwable instanceof RuntimeException &&
                        throwable.getMessage().equals("DB error"))
                .verify();
    }

    private DatasetItem createItem(UUID id, Map<String, JsonNode> data) {
        return DatasetItem.builder()
                .id(id)
                .data(data)
                .build();
    }
}
