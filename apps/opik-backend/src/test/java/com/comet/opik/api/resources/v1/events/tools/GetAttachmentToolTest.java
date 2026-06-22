package com.comet.opik.api.resources.v1.events.tools;

import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.attachment.EntityType;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.S3Config;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GetAttachmentToolTest {

    private static final String WORKSPACE_ID = "ws-" + RandomStringUtils.secure().nextAlphanumeric(8);
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(8);
    private static final String IMAGE_FILE_NAME = "img-" + RandomStringUtils.secure().nextAlphanumeric(8) + ".png";
    private static final String PDF_FILE_NAME = "doc-" + RandomStringUtils.secure().nextAlphanumeric(8) + ".pdf";

    @Mock
    private AttachmentService attachmentService;
    @Mock
    private OpikConfiguration config;
    @Mock
    private S3Config s3Config;

    private GetAttachmentTool tool;
    private TraceToolContext ctx;
    private UUID traceId;
    private UUID projectId;

    @BeforeEach
    void setUp() {
        tool = new GetAttachmentTool(attachmentService, config);
        traceId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        // The project id is carried on the eval context, so the tool never fetches the entity.
        ctx = TraceToolContext.forThread(WORKSPACE_ID, USER, projectId);
        when(config.getS3Config()).thenReturn(s3Config);
    }

    private AttachmentInfo attachment(String fileName, String mimeType) {
        return AttachmentInfo.builder()
                .fileName(fileName)
                .entityType(EntityType.TRACE)
                .entityId(traceId)
                .containerId(projectId)
                .mimeType(mimeType)
                .fileSize(1024L)
                .build();
    }

    private String run(String arguments) {
        return tool.execute(arguments, ctx).block();
    }

    @Test
    void fetchImageFromMinIOStagesBase64AndConfirms() {
        when(s3Config.isMinIO()).thenReturn(true);
        when(attachmentService.getAttachmentInfoByEntity(any(), any(), any()))
                .thenReturn(Mono.just(List.of(attachment(IMAGE_FILE_NAME, "image/png"))));
        byte[] bytes = {1, 2, 3, 4};
        when(attachmentService.downloadAttachment(any(), eq(WORKSPACE_ID)))
                .thenReturn((InputStream) new ByteArrayInputStream(bytes));

        String result = run(
                "{\"type\":\"trace\",\"id\":\"%s\",\"file_name\":\"%s\"}".formatted(traceId, IMAGE_FILE_NAME));

        assertThat(result).contains("\"loaded\":true").contains("\"image\"");
        assertThat(ctx.hasPendingMedia()).isTrue();
        List<MediaPayload> media = ctx.drainPendingMedia();
        assertThat(media).hasSize(1);
        assertThat(media.get(0).category()).isEqualTo(MediaCategory.IMAGE);
        assertThat(media.get(0).base64Data()).isEqualTo(Base64.getEncoder().encodeToString(bytes));
        assertThat(media.get(0).url()).isNull();
    }

    @Test
    void fetchImageFromS3StagesPresignedUrl() {
        String presignedUrl = "https://s3.example/" + RandomStringUtils.secure().nextAlphanumeric(12) + "?sig="
                + RandomStringUtils.secure().nextAlphanumeric(16);
        when(s3Config.isMinIO()).thenReturn(false);
        when(attachmentService.getAttachmentInfoByEntity(any(), any(), any()))
                .thenReturn(Mono.just(List.of(attachment(IMAGE_FILE_NAME, "image/png"))));
        when(attachmentService.presignDownloadUrl(any(), eq(WORKSPACE_ID)))
                .thenReturn(presignedUrl);

        String result = run(
                "{\"type\":\"trace\",\"id\":\"%s\",\"file_name\":\"%s\"}".formatted(traceId, IMAGE_FILE_NAME));

        assertThat(result).contains("\"loaded\":true");
        List<MediaPayload> media = ctx.drainPendingMedia();
        assertThat(media).hasSize(1);
        assertThat(media.get(0).url()).isEqualTo(presignedUrl);
        assertThat(media.get(0).base64Data()).isNull();
    }

    @Test
    void fetchUnknownFileNameReturnsError() {
        when(attachmentService.getAttachmentInfoByEntity(any(), any(), any()))
                .thenReturn(Mono.just(List.of(attachment(IMAGE_FILE_NAME, "image/png"))));
        String unknownName = "other-" + IMAGE_FILE_NAME;

        String result = run("{\"type\":\"trace\",\"id\":\"%s\",\"file_name\":\"%s\"}".formatted(traceId, unknownName));

        assertThat(result).contains("error").contains("No attachment named '" + unknownName + "'");
        assertThat(ctx.hasPendingMedia()).isFalse();
    }

    @Test
    void fetchNonViewableMimeIsDeclined() {
        when(attachmentService.getAttachmentInfoByEntity(any(), any(), any()))
                .thenReturn(Mono.just(List.of(attachment(PDF_FILE_NAME, "application/pdf"))));

        String result = run(
                "{\"type\":\"trace\",\"id\":\"%s\",\"file_name\":\"%s\"}".formatted(traceId, PDF_FILE_NAME));

        assertThat(result).contains("error").contains("not a viewable");
        assertThat(ctx.hasPendingMedia()).isFalse();
    }

    @Test
    void unsupportedTypeReturnsError() {
        String result = run("{\"type\":\"dataset\",\"id\":\"%s\"}".formatted(traceId));

        assertThat(result).contains("error").contains("only type=trace or type=span");
    }

    @Test
    void invalidUuidReturnsError() {
        String result = run(
                "{\"type\":\"trace\",\"id\":\"not-a-uuid\",\"file_name\":\"%s\"}".formatted(IMAGE_FILE_NAME));

        assertThat(result).contains("error").contains("Invalid id format");
    }

    @Test
    void missingTypeReturnsError() {
        String result = run("{\"id\":\"%s\",\"file_name\":\"%s\"}".formatted(traceId, IMAGE_FILE_NAME));

        assertThat(result).contains("error").contains("Missing required argument: type");
    }

    @Test
    void missingFileNameReturnsError() {
        String result = run("{\"type\":\"trace\",\"id\":\"%s\"}".formatted(traceId));

        assertThat(result).contains("error").contains("Missing required argument: file_name");
    }

    // Size-cap tests (MAX_INJECTED_BYTES = 20 MB)

    @Test
    void fetchFromMinIORejectWhenSizeCapExceeded() {
        when(s3Config.isMinIO()).thenReturn(true);
        long overLimit = TraceToolContext.MAX_INJECTED_BYTES + 1;
        AttachmentInfo bigAttachment = AttachmentInfo.builder()
                .fileName(IMAGE_FILE_NAME)
                .entityType(EntityType.TRACE)
                .entityId(traceId)
                .containerId(projectId)
                .mimeType("image/png")
                .fileSize(overLimit)
                .build();
        when(attachmentService.getAttachmentInfoByEntity(any(), any(), any()))
                .thenReturn(Mono.just(List.of(bigAttachment)));

        String result = run(
                "{\"type\":\"trace\",\"id\":\"%s\",\"file_name\":\"%s\"}".formatted(traceId, IMAGE_FILE_NAME));

        assertThat(result).contains("error").contains("size limit");
        assertThat(ctx.hasPendingMedia()).isFalse();
    }

    @Test
    void fetchFromS3RejectWhenSizeCapExceeded() {
        when(s3Config.isMinIO()).thenReturn(false);
        long overLimit = TraceToolContext.MAX_INJECTED_BYTES + 1;
        AttachmentInfo bigAttachment = AttachmentInfo.builder()
                .fileName(IMAGE_FILE_NAME)
                .entityType(EntityType.TRACE)
                .entityId(traceId)
                .containerId(projectId)
                .mimeType("image/png")
                .fileSize(overLimit)
                .build();
        when(attachmentService.getAttachmentInfoByEntity(any(), any(), any()))
                .thenReturn(Mono.just(List.of(bigAttachment)));

        String result = run(
                "{\"type\":\"trace\",\"id\":\"%s\",\"file_name\":\"%s\"}".formatted(traceId, IMAGE_FILE_NAME));

        assertThat(result).contains("error").contains("size limit");
        assertThat(ctx.hasPendingMedia()).isFalse();
    }

    @Test
    void fetchFromMinIORejectSecondAttachmentThatPushesOverCap() {
        when(s3Config.isMinIO()).thenReturn(true);

        // First attachment consumes most of the budget
        long firstSize = TraceToolContext.MAX_INJECTED_BYTES - 1024;
        byte[] firstBytes = new byte[4]; // actual content doesn't matter for the cap logic
        AttachmentInfo firstInfo = AttachmentInfo.builder()
                .fileName(IMAGE_FILE_NAME)
                .entityType(EntityType.TRACE)
                .entityId(traceId)
                .containerId(projectId)
                .mimeType("image/png")
                .fileSize(firstSize)
                .build();
        when(attachmentService.getAttachmentInfoByEntity(any(), any(), any()))
                .thenReturn(Mono.just(List.of(firstInfo)));
        when(attachmentService.downloadAttachment(any(), eq(WORKSPACE_ID)))
                .thenReturn(new ByteArrayInputStream(firstBytes));

        String firstResult = run(
                "{\"type\":\"trace\",\"id\":\"%s\",\"file_name\":\"%s\"}".formatted(traceId, IMAGE_FILE_NAME));
        assertThat(firstResult).contains("\"loaded\":true");

        // Second attachment pushes total over 20 MB
        String secondFileName = "second-" + IMAGE_FILE_NAME;
        long secondSize = 2048; // 1024 + 2048 > 1024 remaining
        AttachmentInfo secondInfo = AttachmentInfo.builder()
                .fileName(secondFileName)
                .entityType(EntityType.TRACE)
                .entityId(traceId)
                .containerId(projectId)
                .mimeType("image/png")
                .fileSize(secondSize)
                .build();
        when(attachmentService.getAttachmentInfoByEntity(any(), any(), any()))
                .thenReturn(Mono.just(List.of(secondInfo)));

        String secondResult = run(
                "{\"type\":\"trace\",\"id\":\"%s\",\"file_name\":\"%s\"}".formatted(traceId, secondFileName));
        assertThat(secondResult).contains("error").contains("size limit");
    }
}
