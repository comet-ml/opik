package com.comet.opik.domain;

import com.comet.opik.api.Span;
import com.comet.opik.api.Visibility;
import com.comet.opik.domain.attachment.AttachmentReinjectorService;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.domain.attachment.AttachmentStripperService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.lock.LockService;
import com.google.common.eventbus.EventBus;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpanServiceTest {

    private SpanService spanService;

    @Mock
    private SpanDAO spanDAO;

    @Mock
    private ProjectService projectService;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private LockService lockService;

    @Mock
    private CommentService commentService;

    @Mock
    private FeedbackScoreService feedbackScoreService;

    @Mock
    private AttachmentService attachmentService;

    @Mock
    private AttachmentStripperService attachmentStripperService;

    @Mock
    private AttachmentReinjectorService attachmentReinjectorService;

    @Mock
    private EventBus eventBus;

    @BeforeEach
    void setUp() {
        spanService = new SpanService(
                spanDAO,
                projectService,
                idGenerator,
                lockService,
                commentService,
                feedbackScoreService,
                attachmentService,
                attachmentStripperService,
                attachmentReinjectorService,
                eventBus);
    }

    @Test
    void getByIdWhenProjectMissingForPrivateVisibilityThrowsNotFound() {
        var spanId = UUID.randomUUID();
        var projectId = UUID.randomUUID();
        var span = Span.builder()
                .id(spanId)
                .projectId(projectId)
                .build();

        when(spanDAO.getById(spanId)).thenReturn(Mono.just(span));
        when(projectService.getOrFail(projectId)).thenReturn(Mono.error(new NotFoundException()));

        StepVerifier.create(spanService.getById(spanId, true)
                .contextWrite(ctx -> ctx.put(RequestContext.VISIBILITY, Visibility.PRIVATE)))
                .expectError(NotFoundException.class)
                .verify();

        verifyNoInteractions(attachmentReinjectorService);
    }
}
