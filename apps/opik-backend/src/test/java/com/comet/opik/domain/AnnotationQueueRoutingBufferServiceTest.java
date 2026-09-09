package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.infrastructure.AnnotationQueueRoutingConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RMapReactive;
import org.redisson.api.RScoredSortedSetReactive;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the debounce buffer's flush lifecycle, and above all the order it does things in: the durability of
 * the whole feature rests on work leaving the buffer only once the stream has it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnnotationQueueRoutingBufferServiceTest {

    private static final String WORKSPACE_ID = "workspace-1";
    private static final String USER_NAME = "user-1";
    private static final AnnotationQueue.AnnotationScope TRACE = AnnotationQueue.AnnotationScope.TRACE;

    @Mock
    private RedissonReactiveClient redisClient;
    @Mock
    private RScoredSortedSetReactive<Object> pending;
    @Mock
    private RMapReactive<String, String> authors;
    @Mock
    private RMapReactive<String, String> scoreNames;
    @Mock
    private AnnotationQueueRoutingPublisher publisher;

    private AnnotationQueueRoutingConfig config;
    private AnnotationQueueRoutingBufferService service;

    @BeforeEach
    void setUp() {
        config = new AnnotationQueueRoutingConfig();

        when(redisClient.getScoredSortedSet(AnnotationQueueRoutingBufferService.PENDING_SET_KEY))
                .thenReturn(pending);
        when(redisClient.<String, String>getMap(AnnotationQueueRoutingBufferService.PENDING_AUTHORS_KEY))
                .thenReturn(authors);
        when(redisClient.<String, String>getMap(AnnotationQueueRoutingBufferService.PENDING_SCORE_NAMES_KEY))
                .thenReturn(scoreNames);

        when(pending.addIfAbsent(anyDouble(), any())).thenReturn(Mono.just(true));
        when(pending.removeAll(any())).thenReturn(Mono.just(true));
        when(authors.fastPut(anyString(), anyString())).thenReturn(Mono.just(true));
        when(authors.fastRemove(any(String[].class))).thenReturn(Mono.just(1L));
        when(scoreNames.fastPut(anyString(), anyString())).thenReturn(Mono.just(true));
        when(scoreNames.fastRemove(any(String[].class))).thenReturn(Mono.just(1L));

        service = new AnnotationQueueRoutingBufferService(redisClient, config, publisher);
    }

    @Nested
    @DisplayName("Recording")
    class RecordingTests {

        @Test
        void recordsOneMemberPerEntityWithTheAuthorAndScoreNames() {
            UUID traceId = UUID.randomUUID();

            service.record(WORKSPACE_ID, USER_NAME, TRACE, Set.of(traceId), Set.of("relevance")).block();

            String expectedMember = "%s:trace:%s".formatted(WORKSPACE_ID, traceId);
            verify(pending).addIfAbsent(anyDouble(), eq(expectedMember));
            verify(authors).fastPut(expectedMember, USER_NAME);
            verify(scoreNames).fastPut(expectedMember, "relevance");
        }

        /**
         * addIfAbsent, not addIfGreater: the deadline belongs to the entity's first score. Repeated scoring
         * must not push a trace's review out indefinitely, which is what a resetting timer would do.
         */
        @Test
        void doesNotResetTheDeadlineOnRepeatedScoring() {
            UUID traceId = UUID.randomUUID();

            service.record(WORKSPACE_ID, USER_NAME, TRACE, Set.of(traceId), Set.of("relevance")).block();
            service.record(WORKSPACE_ID, USER_NAME, TRACE, Set.of(traceId), Set.of("hallucination")).block();

            verify(pending, never()).addScore(any(), anyDouble());
            verify(pending, org.mockito.Mockito.times(2)).addIfAbsent(anyDouble(), any());
        }

        @Test
        void writesNoScoreNamesWhenTheEmitterDidNotSay() {
            service.record(WORKSPACE_ID, USER_NAME, TRACE, Set.of(UUID.randomUUID()), Set.of()).block();

            verify(scoreNames, never()).fastPut(anyString(), anyString());
        }

        @Test
        void recordsNothingWhenDisabled() {
            config.setEnabled(false);

            service.record(WORKSPACE_ID, USER_NAME, TRACE, Set.of(UUID.randomUUID()), Set.of("relevance")).block();

            verify(pending, never()).addIfAbsent(anyDouble(), any());
        }
    }

    @Nested
    @DisplayName("Flushing")
    class FlushingTests {

        @Test
        void publishesNothingWhenNothingIsDue() {
            givenDue(List.of());

            assertThat(service.flush().block()).isZero();
            verify(publisher, never()).enqueue(anyString(), anyString(), any(), any(), any());
        }

        /**
         * The ordering the feature's durability depends on. A duplicate message costs nothing - the consumer
         * re-derives state and addItems excludes what a queue has held - but removing first and then failing
         * to publish drops the entity for good, and there is no backfill.
         */
        @Test
        void publishesBeforeRemovingFromTheBuffer() {
            UUID traceId = UUID.randomUUID();
            givenDue(List.of(member(traceId)));
            givenAuthors(Map.of(member(traceId), USER_NAME));
            when(publisher.enqueue(anyString(), anyString(), any(), any(), any())).thenReturn(Mono.empty());

            assertThat(service.flush().block()).isEqualTo(1L);

            InOrder order = inOrder(publisher, pending, authors);
            order.verify(publisher).enqueue(eq(WORKSPACE_ID), eq(USER_NAME), eq(TRACE), any(), any());
            order.verify(pending).removeAll(any());
            order.verify(authors).fastRemove(any(String[].class));
        }

        @Test
        void leavesEntitiesPendingWhenPublishingFails() {
            UUID traceId = UUID.randomUUID();
            givenDue(List.of(member(traceId)));
            givenAuthors(Map.of(member(traceId), USER_NAME));
            when(publisher.enqueue(anyString(), anyString(), any(), any(), any()))
                    .thenReturn(Mono.error(new IllegalStateException("redis down")));

            // Swallowed so one group cannot strand the others, and reported as nothing published.
            assertThat(service.flush().block()).isZero();

            verify(pending, never()).removeAll(any());
            verify(authors, never()).fastRemove(any(String[].class));
            verify(scoreNames, never()).fastRemove(any(String[].class));
        }

        @Test
        void oneFailingGroupDoesNotStopTheOthers() {
            UUID failing = UUID.randomUUID();
            UUID succeeding = UUID.randomUUID();
            givenDue(List.of(member(failing), member("workspace-2", succeeding)));
            givenAuthors(Map.of(member(failing), USER_NAME, member("workspace-2", succeeding), USER_NAME));
            when(publisher.enqueue(eq(WORKSPACE_ID), anyString(), any(), any(), any()))
                    .thenReturn(Mono.error(new IllegalStateException("redis down")));
            when(publisher.enqueue(eq("workspace-2"), anyString(), any(), any(), any())).thenReturn(Mono.empty());

            assertThat(service.flush().block()).isEqualTo(1L);

            verify(publisher).enqueue(eq("workspace-2"), anyString(), any(), any(), any());
        }

        /** One message per workspace, scope and author - the author is stamped on the queue item. */
        @Test
        void groupsByWorkspaceScopeAndAuthor() {
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            UUID c = UUID.randomUUID();
            givenDue(List.of(member(a), member(b), member("workspace-2", c)));
            givenAuthors(Map.of(
                    member(a), USER_NAME,
                    member(b), "someone-else",
                    member("workspace-2", c), USER_NAME));
            when(publisher.enqueue(anyString(), anyString(), any(), any(), any())).thenReturn(Mono.empty());

            assertThat(service.flush().block()).isEqualTo(3L);

            verify(publisher, org.mockito.Mockito.times(3))
                    .enqueue(anyString(), anyString(), any(), any(), any());
        }

        @Test
        void carriesEachEntitysScoreNamesThroughToTheMessage() {
            UUID traceId = UUID.randomUUID();
            givenDue(List.of(member(traceId)));
            givenAuthors(Map.of(member(traceId), USER_NAME));
            when(scoreNames.getAll(any())).thenReturn(Mono.just(Map.of(member(traceId), "relevancesafety")));
            when(publisher.enqueue(anyString(), anyString(), any(), any(), any())).thenReturn(Mono.empty());

            service.flush().block();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<UUID, Set<String>>> captor = ArgumentCaptor.forClass(Map.class);
            verify(publisher).enqueue(anyString(), anyString(), any(), any(), captor.capture());
            assertThat(captor.getValue()).containsEntry(traceId, Set.of("relevance", "safety"));
        }

        @Test
        void publishesNothingWhenDisabled() {
            config.setEnabled(false);

            assertThat(service.flush().block()).isZero();
            verify(pending, never()).valueRange(anyDouble(), any(Boolean.class), anyDouble(),
                    any(Boolean.class), anyInt(), anyInt());
        }
    }

    private String member(UUID entityId) {
        return member(WORKSPACE_ID, entityId);
    }

    private String member(String workspaceId, UUID entityId) {
        return "%s:trace:%s".formatted(workspaceId, entityId);
    }

    private void givenDue(List<String> members) {
        when(pending.valueRange(anyDouble(), any(Boolean.class), anyDouble(), any(Boolean.class), anyInt(),
                anyInt())).thenReturn(Mono.just((Collection<Object>) List.<Object>copyOf(members)));
        when(scoreNames.getAll(any())).thenReturn(Mono.just(Map.of()));
        when(authors.getAll(any())).thenReturn(Mono.just(Map.of()));
    }

    private void givenAuthors(Map<String, String> authorByMember) {
        when(authors.getAll(any())).thenReturn(Mono.just(authorByMember));
    }
}
