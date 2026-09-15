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
import org.redisson.api.RSetReactive;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
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
    // One mock per key rather than one for all of them: sharing a single mock would let a wrong
    // scoreNamesKey(), or one entity's names landing on another's key, pass unnoticed.
    private final Map<String, RSetReactive<String>> nameSets = new HashMap<>();
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
        when(redisClient.<String>getSet(anyString())).thenAnswer(inv -> nameSet(inv.getArgument(0)));

        when(pending.addIfAbsent(anyDouble(), any())).thenReturn(Mono.just(true));
        when(pending.removeAll(any())).thenReturn(Mono.just(true));
        when(authors.fastPut(anyString(), anyString())).thenReturn(Mono.just(true));
        when(authors.fastRemove(any(String[].class))).thenReturn(Mono.just(1L));
        nameSets.clear();

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
            verify(nameSet(scoreNamesKey(expectedMember))).addAll((Collection<String>) Set.of("relevance"));
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

            assertThat(nameSets).isEmpty();
        }

        /**
         * A set, so two scores on the same entity inside one window both contribute. Overwriting would
         * leave the freshness check blind to every event but the last, which is the whole point of
         * carrying the names.
         */
        @Test
        void unionsScoreNamesAcrossEventsInTheSameWindow() {
            UUID traceId = UUID.randomUUID();

            service.record(WORKSPACE_ID, USER_NAME, TRACE, Set.of(traceId), Set.of("relevance")).block();
            service.record(WORKSPACE_ID, USER_NAME, TRACE, Set.of(traceId), Set.of("hallucination")).block();

            String key = scoreNamesKey(member(traceId));
            verify(nameSet(key)).addAll((Collection<String>) Set.of("relevance"));
            verify(nameSet(key)).addAll((Collection<String>) Set.of("hallucination"));
            assertThat(nameSets).hasSize(1); // both went to the one key for that entity
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
            nameSets.values().forEach(m -> verify(m, never()).removeAll(anyCollection()));
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
            when(nameSet(scoreNamesKey(member(traceId))).readAll())
                    .thenReturn(Mono.just(Set.of("relevance", "safety")));
            when(publisher.enqueue(anyString(), anyString(), any(), any(), any())).thenReturn(Mono.empty());

            service.flush().block();

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<UUID, Set<String>>> captor = ArgumentCaptor.forClass(Map.class);
            verify(publisher).enqueue(anyString(), anyString(), any(), any(), captor.capture());
            assertThat(captor.getValue()).containsEntry(traceId, Set.of("relevance", "safety"));
        }

        /**
         * Two things, and deliberately not a third. It asserts that the read is bounded by jobBatchSize,
         * and that only the members actually published are removed - so whatever Redis left behind stays
         * pending. It does not assert that the backlog is drained oldest-first: that follows from
         * ZRANGEBYSCORE returning ascending by score, where the score is the due timestamp, and Redis
         * honouring LIMIT. Neither is this class's to verify with a mocked client, and pretending
         * otherwise is how a test ends up named for a guarantee it never exercises.
         */
        @Test
        void boundsTheReadToOneBatchAndRemovesOnlyWhatItPublished() {
            config.setJobBatchSize(3);
            List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
            List<String> firstPage = ids.stream().map(id -> member(id)).toList();
            givenDue(firstPage);
            givenAuthors(firstPage.stream()
                    .collect(java.util.stream.Collectors.toMap(m -> m, m -> USER_NAME)));
            when(publisher.enqueue(anyString(), anyString(), any(), any(), any())).thenReturn(Mono.empty());

            assertThat(service.flush().block()).isEqualTo(3L);

            // The read asked Redis for no more than a batch.
            verify(pending).valueRange(anyDouble(), any(Boolean.class), anyDouble(), any(Boolean.class),
                    eq(0), eq(3));

            // And only what was published is removed, so anything beyond the page is still pending.
            @SuppressWarnings("unchecked")
            ArgumentCaptor<Collection<Object>> removed = ArgumentCaptor.forClass(Collection.class);
            verify(pending).removeAll(removed.capture());
            assertThat(removed.getValue()).hasSize(3).containsExactlyInAnyOrderElementsOf(firstPage);
        }

        /** Each entity's names go to its own key, so one entity cannot see another's. */
        @Test
        void keepsEachEntitysScoreNamesOnItsOwnKey() {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();

            service.record(WORKSPACE_ID, USER_NAME, TRACE, Set.of(first), Set.of("relevance")).block();
            service.record(WORKSPACE_ID, USER_NAME, TRACE, Set.of(second), Set.of("safety")).block();

            assertThat(nameSets.keySet()).containsExactlyInAnyOrder(
                    scoreNamesKey(member(first)), scoreNamesKey(member(second)));
            verify(nameSet(scoreNamesKey(member(first)))).addAll((Collection<String>) Set.of("relevance"));
            verify(nameSet(scoreNamesKey(member(second)))).addAll((Collection<String>) Set.of("safety"));
        }

        /**
         * A score arriving between the read and the removal must survive. Deleting the key would erase a
         * name the published message knows nothing about, leaving the freshness check blind to exactly the
         * score it exists to catch - so removal is by value.
         */
        @Test
        void removesOnlyTheNamesItPublishedRatherThanTheWholeKey() {
            UUID traceId = UUID.randomUUID();
            String key = scoreNamesKey(member(traceId));
            givenDue(List.of(member(traceId)));
            givenAuthors(Map.of(member(traceId), USER_NAME));
            when(nameSet(key).readAll()).thenReturn(Mono.just(Set.of("relevance")));
            when(publisher.enqueue(anyString(), anyString(), any(), any(), any())).thenReturn(Mono.empty());

            service.flush().block();

            verify(nameSet(key)).removeAll((Collection<String>) Set.of("relevance"));
            verify(nameSet(key), never()).delete();
        }

        @Test
        void publishesNothingWhenDisabled() {
            config.setEnabled(false);

            assertThat(service.flush().block()).isZero();
            verify(pending, never()).valueRange(anyDouble(), any(Boolean.class), anyDouble(),
                    any(Boolean.class), anyInt(), anyInt());
        }
    }

    @SuppressWarnings("unchecked")
    private RSetReactive<String> nameSet(String key) {
        return nameSets.computeIfAbsent(key, k -> {
            RSetReactive<String> m = org.mockito.Mockito.mock(RSetReactive.class);
            when(m.addAll(anyCollection())).thenReturn(Mono.just(true));
            when(m.removeAll(anyCollection())).thenReturn(Mono.just(true));
            when(m.readAll()).thenReturn(Mono.just(Set.of()));
            when(m.delete()).thenReturn(Mono.just(true));
            when(m.expire(any(java.time.Duration.class))).thenReturn(Mono.just(true));
            return m;
        });
    }

    private String scoreNamesKey(String member) {
        return "annotation-queue:routing:pending-score-names:" + member;
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
        when(authors.getAll(any())).thenReturn(Mono.just(Map.of()));
    }

    private void givenAuthors(Map<String, String> authorByMember) {
        when(authors.getAll(any())).thenReturn(Mono.just(authorByMember));
    }
}
