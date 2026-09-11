package com.comet.opik.utils;

import com.comet.opik.api.attachment.Attachment;
import com.comet.opik.api.attachment.AttachmentInfoHolder;
import com.comet.opik.api.attachment.DeleteAttachmentsRequest;
import com.comet.opik.api.attachment.StartMultipartUploadRequest;
import org.apache.tika.Tika;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public class AttachmentUtilsTest {
    public static final String[] IGNORED_ATTACHMENT_PAGE_LIST = {"content"};
    public static final String[] IGNORED_ATTACHMENT_LIST = {"link"};

    private static final Tika tika = new Tika();

    public static void verifyPage(Attachment.AttachmentPage actual, Attachment.AttachmentPage expected) {
        assertThat(actual)
                .usingRecursiveComparison()
                .ignoringFields(IGNORED_ATTACHMENT_PAGE_LIST)
                .isEqualTo(expected);

        assertThat(actual.content())
                .usingRecursiveComparison()
                .ignoringFields(IGNORED_ATTACHMENT_LIST)
                .isEqualTo(expected.content());
    }

    public static Attachment.AttachmentPage prepareExpectedPage(StartMultipartUploadRequest startUploadRequest,
            long fileSize) {
        Attachment expectedAttachment = Attachment.builder()
                .fileName(startUploadRequest.fileName())
                .fileSize(fileSize)
                .mimeType(startUploadRequest.mimeType() != null
                        ? startUploadRequest.mimeType()
                        : tika.detect(startUploadRequest.fileName()))
                .build();

        return Attachment.AttachmentPage.builder()
                .page(1)
                .size(1)
                .total(1)
                .sortableBy(List.of())
                .content(List.of(expectedAttachment))
                .build();
    }

    public static DeleteAttachmentsRequest prepareDeleteRequest(AttachmentInfoHolder attachmentInfoHolder,
            UUID containerId) {
        return DeleteAttachmentsRequest.builder()
                .containerId(containerId)
                .entityType(attachmentInfoHolder.entityType())
                .entityId(attachmentInfoHolder.entityId())
                .fileNames(Set.of(attachmentInfoHolder.fileName()))
                .build();
    }
}
