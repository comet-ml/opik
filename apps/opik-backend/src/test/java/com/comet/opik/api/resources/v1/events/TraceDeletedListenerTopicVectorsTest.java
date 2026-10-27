package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.TracesDeleted;
import com.comet.opik.domain.CommentService;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.ailabels.AiLabelStatusDAO;
import com.comet.opik.domain.ailabels.AiLabelsDAO;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.domain.topics.TopicVectorsDAO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("trace delete cascade: topic vectors")
class TraceDeletedListenerTopicVectorsTest {

    private final FeedbackScoreService feedbackScoreService = mock(FeedbackScoreService.class);
    private final CommentService commentService = mock(CommentService.class);
    private final AttachmentService attachmentService = mock(AttachmentService.class);
    private final SpanService spanService = mock(SpanService.class);
    private final AiLabelsDAO aiLabelsDAO = mock(AiLabelsDAO.class);
    private final AiLabelStatusDAO aiLabelStatusDAO = mock(AiLabelStatusDAO.class);
    private final TopicVectorsDAO topicVectorsDAO = mock(TopicVectorsDAO.class);
    private final List<String> deleted = new CopyOnWriteArrayList<>();

    private TraceDeletedListener listener;

    @BeforeEach
    void setUp() {
        when(feedbackScoreService.deleteByTraceIds(any(), any())).thenReturn(Mono.empty());
        when(commentService.deleteByEntityIds(any(), any(), any())).thenReturn(Mono.just(0L));
        when(attachmentService.deleteByEntityIds(any(), any(), any())).thenReturn(Mono.just(0L));
        when(aiLabelsDAO.deleteByTraceIds(any(), any())).thenReturn(record("ai_labels"));
        when(aiLabelStatusDAO.deleteByTraceIds(any(), any())).thenReturn(record("ai_label_status"));
        when(topicVectorsDAO.deleteByTraceIds(any(), any())).thenReturn(record("topic_vectors"));
        when(spanService.deleteByTraceIds(any(), any())).thenReturn(record("spans"));
        listener = new TraceDeletedListener(feedbackScoreService, commentService, attachmentService, spanService,
                aiLabelsDAO, aiLabelStatusDAO, topicVectorsDAO);
    }

    @Test
    @DisplayName("the vectors are deleted before ai-labels")
    void order() {
        listener.onTracesDeleted(event());

        assertThat(deleted).containsExactly("topic_vectors", "ai_labels", "ai_label_status", "spans");
    }

    @Test
    @DisplayName("a failed ai-labels delete still deletes the vectors and the spans")
    void aiLabelsDeleteFails() {
        when(aiLabelsDAO.deleteByTraceIds(any(), any())).thenReturn(Mono.error(new IllegalStateException("down")));

        listener.onTracesDeleted(event());

        assertThat(deleted).containsExactly("topic_vectors", "spans");
    }

    @Test
    @DisplayName("a failed vectors delete still deletes ai-labels and the spans")
    void vectorsDeleteFails() {
        when(topicVectorsDAO.deleteByTraceIds(any(), any()))
                .thenReturn(Mono.error(new IllegalStateException("down")));

        listener.onTracesDeleted(event());

        assertThat(deleted).containsExactly("ai_labels", "ai_label_status", "spans");
    }

    private Mono<Void> record(String table) {
        return Mono.fromRunnable(() -> deleted.add(table));
    }

    private static TracesDeleted event() {
        return new TracesDeleted(Set.of(UUID.randomUUID()), UUID.randomUUID(), UUID.randomUUID().toString(), "user");
    }
}
