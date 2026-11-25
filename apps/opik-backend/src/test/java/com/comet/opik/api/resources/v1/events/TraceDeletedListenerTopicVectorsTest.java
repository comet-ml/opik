package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.TracesDeleted;
import com.comet.opik.domain.CommentDAO;
import com.comet.opik.domain.CommentService;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.domain.ailabels.AiLabelStatusDAO;
import com.comet.opik.domain.ailabels.AiLabelsDAO;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.domain.topics.TopicVectorsDAO;
import com.comet.opik.domain.topics.TraceTopicsDAO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.comet.opik.api.attachment.EntityType.TRACE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("trace delete cascade: topic vectors and trace topics")
class TraceDeletedListenerTopicVectorsTest {

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    private final Set<UUID> traceIds = Set.of(ID_GENERATOR.generateId());
    private final UUID projectId = ID_GENERATOR.generateId();
    private final String workspaceId = ID_GENERATOR.generateId().toString();
    private final FeedbackScoreService feedbackScoreService = mock(FeedbackScoreService.class);
    private final CommentService commentService = mock(CommentService.class);
    private final AttachmentService attachmentService = mock(AttachmentService.class);
    private final SpanService spanService = mock(SpanService.class);
    private final AiLabelsDAO aiLabelsDAO = mock(AiLabelsDAO.class);
    private final AiLabelStatusDAO aiLabelStatusDAO = mock(AiLabelStatusDAO.class);
    private final TopicVectorsDAO topicVectorsDAO = mock(TopicVectorsDAO.class);
    private final TraceTopicsDAO traceTopicsDAO = mock(TraceTopicsDAO.class);
    private final List<String> deleted = new CopyOnWriteArrayList<>();

    private TraceDeletedListener listener;

    @BeforeEach
    void setUp() {
        when(feedbackScoreService.deleteByTraceIds(traceIds, projectId)).thenReturn(Mono.empty());
        when(commentService.deleteByEntityIds(CommentDAO.EntityType.TRACE, traceIds, projectId))
                .thenReturn(Mono.just(0L));
        when(attachmentService.deleteByEntityIds(TRACE, traceIds, projectId)).thenReturn(Mono.just(0L));
        when(aiLabelsDAO.deleteByTraceIds(traceIds, projectId)).thenReturn(record("ai_labels"));
        when(aiLabelStatusDAO.deleteByTraceIds(traceIds, projectId)).thenReturn(record("ai_label_status"));
        when(topicVectorsDAO.deleteByTraceIds(traceIds, projectId)).thenReturn(record("topic_vectors"));
        when(traceTopicsDAO.deleteByTraceIds(traceIds, projectId)).thenReturn(record("trace_topics"));
        when(spanService.deleteByTraceIds(traceIds, projectId)).thenReturn(record("spans"));
        listener = new TraceDeletedListener(feedbackScoreService, commentService, attachmentService, spanService,
                aiLabelsDAO, aiLabelStatusDAO, topicVectorsDAO, traceTopicsDAO);
    }

    @Test
    @DisplayName("the vectors are deleted after the status rows, so a tick that writes a vector later finds the "
            + "status gone; the trace topics go after the vectors")
    void order() {
        listener.onTracesDeleted(event());

        assertThat(deleted).containsExactly("ai_labels", "ai_label_status", "topic_vectors", "trace_topics", "spans");
    }

    @Test
    @DisplayName("a failed ai-labels delete still deletes the vectors and the spans")
    void aiLabelsDeleteFails() {
        when(aiLabelsDAO.deleteByTraceIds(traceIds, projectId))
                .thenReturn(Mono.error(new IllegalStateException("down")));

        listener.onTracesDeleted(event());

        assertThat(deleted).containsExactly("topic_vectors", "trace_topics", "spans");
    }

    @Test
    @DisplayName("a failed vectors delete still deletes ai-labels and the spans")
    void vectorsDeleteFails() {
        when(topicVectorsDAO.deleteByTraceIds(traceIds, projectId))
                .thenReturn(Mono.error(new IllegalStateException("down")));

        listener.onTracesDeleted(event());

        assertThat(deleted).containsExactly("ai_labels", "ai_label_status", "trace_topics", "spans");
    }

    @Test
    @DisplayName("a failed trace topics delete still deletes the rest")
    void traceTopicsDeleteFails() {
        when(traceTopicsDAO.deleteByTraceIds(traceIds, projectId))
                .thenReturn(Mono.error(new IllegalStateException("down")));

        listener.onTracesDeleted(event());

        assertThat(deleted).containsExactly("ai_labels", "ai_label_status", "topic_vectors", "spans");
    }

    private Mono<Void> record(String table) {
        return Mono.fromRunnable(() -> deleted.add(table));
    }

    private TracesDeleted event() {
        return new TracesDeleted(traceIds, projectId, workspaceId, "user");
    }
}
