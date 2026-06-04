package com.comet.opik.domain;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RMapReactive;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnnotationQueueItemLockServiceTest {

    private static final String WORKSPACE_ID = "test-workspace";
    private static final UUID QUEUE_ID = UUID.randomUUID();
    private static final UUID ITEM_ID = UUID.randomUUID();
    private static final String USER_ALICE = "alice";
    private static final String USER_BOB = "bob";

    @Mock
    private RedissonReactiveClient redisClient;
    @Mock
    private RMapReactive<String, String> mapReactive;

    private AnnotationQueueItemLockServiceImpl lockService;

    @BeforeEach
    void setUp() {
        var dummyLockService = new DummyLockService();
        lockService = new AnnotationQueueItemLockServiceImpl(redisClient, dummyLockService);
        lenient().when(redisClient.<String, String>getMap(anyString(), any(org.redisson.client.codec.Codec.class)))
                .thenReturn(mapReactive);
    }

    private String field(UUID itemId, String userName) {
        return itemId + ":" + userName;
    }

    private String futureExpiry() {
        return String.valueOf(System.currentTimeMillis() + 300_000);
    }

    private String pastExpiry() {
        return String.valueOf(System.currentTimeMillis() - 10_000);
    }

    @Nested
    @DisplayName("tryLock")
    class TryLock {

        @Test
        @DisplayName("should acquire lock when no existing locks")
        void shouldAcquireLockWhenEmpty() {
            when(mapReactive.readAllMap()).thenReturn(Mono.just(Map.of()));
            when(mapReactive.put(anyString(), anyString())).thenReturn(Mono.just(""));

            StepVerifier.create(lockService.tryLock(
                    WORKSPACE_ID, QUEUE_ID, ITEM_ID, USER_ALICE, 1, 0, 5))
                    .assertNext(result -> {
                        assertThat(result.acquired()).isTrue();
                        assertThat(result.itemId()).isEqualTo(ITEM_ID);
                        assertThat(result.lockedBy()).isEqualTo(USER_ALICE);
                        assertThat(result.expiresAt()).isNotNull();
                    })
                    .verifyComplete();

            verify(mapReactive).put(eq(field(ITEM_ID, USER_ALICE)), anyString());
        }

        @Test
        @DisplayName("should refresh lock when user already holds it")
        void shouldRefreshWhenUserAlreadyHoldsLock() {
            var originalExpiry = Instant.ofEpochMilli(System.currentTimeMillis() + 10_000);
            when(mapReactive.readAllMap()).thenReturn(Mono.just(
                    Map.of(field(ITEM_ID, USER_ALICE), String.valueOf(originalExpiry.toEpochMilli()))));
            when(mapReactive.put(anyString(), anyString())).thenReturn(Mono.just(""));

            StepVerifier.create(lockService.tryLock(
                    WORKSPACE_ID, QUEUE_ID, ITEM_ID, USER_ALICE, 1, 0, 5))
                    .assertNext(result -> {
                        assertThat(result.acquired()).isTrue();
                        assertThat(result.expiresAt()).isAfter(originalExpiry);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should deny lock when all slots taken by other users")
        void shouldDenyWhenAllSlotsTaken() {
            when(mapReactive.readAllMap()).thenReturn(Mono.just(
                    Map.of(field(ITEM_ID, USER_BOB), futureExpiry())));

            StepVerifier.create(lockService.tryLock(
                    WORKSPACE_ID, QUEUE_ID, ITEM_ID, USER_ALICE, 1, 0, 5))
                    .assertNext(result -> {
                        assertThat(result.acquired()).isFalse();
                        assertThat(result.expiresAt()).isNull();
                    })
                    .verifyComplete();

            verify(mapReactive, never()).put(anyString(), anyString());
        }

        @Test
        @DisplayName("should deny lock when scored + locks fill all slots")
        void shouldDenyWhenScoredPlusLocksFillSlots() {
            when(mapReactive.readAllMap()).thenReturn(Mono.just(
                    Map.of(field(ITEM_ID, USER_BOB), futureExpiry())));

            StepVerifier.create(lockService.tryLock(
                    WORKSPACE_ID, QUEUE_ID, ITEM_ID, USER_ALICE, 2, 1, 5))
                    .assertNext(result -> assertThat(result.acquired()).isFalse())
                    .verifyComplete();
        }

        @Test
        @DisplayName("should acquire lock when slots available with multi-annotator")
        void shouldAcquireWhenSlotsAvailable() {
            when(mapReactive.readAllMap()).thenReturn(Mono.just(
                    Map.of(field(ITEM_ID, USER_BOB), futureExpiry())));
            when(mapReactive.put(anyString(), anyString())).thenReturn(Mono.just(""));

            StepVerifier.create(lockService.tryLock(
                    WORKSPACE_ID, QUEUE_ID, ITEM_ID, USER_ALICE, 2, 0, 5))
                    .assertNext(result -> assertThat(result.acquired()).isTrue())
                    .verifyComplete();
        }

        @Test
        @DisplayName("should ignore expired locks when counting")
        void shouldIgnoreExpiredLocks() {
            when(mapReactive.readAllMap()).thenReturn(Mono.just(
                    Map.of(field(ITEM_ID, USER_BOB), pastExpiry())));
            when(mapReactive.put(anyString(), anyString())).thenReturn(Mono.just(""));

            StepVerifier.create(lockService.tryLock(
                    WORKSPACE_ID, QUEUE_ID, ITEM_ID, USER_ALICE, 1, 0, 5))
                    .assertNext(result -> assertThat(result.acquired()).isTrue())
                    .verifyComplete();
        }

        @Test
        @DisplayName("should only count locks for the same item")
        void shouldOnlyCountLocksForSameItem() {
            var otherItemId = UUID.randomUUID();
            when(mapReactive.readAllMap()).thenReturn(Mono.just(
                    Map.of(field(otherItemId, USER_BOB), futureExpiry())));
            when(mapReactive.put(anyString(), anyString())).thenReturn(Mono.just(""));

            StepVerifier.create(lockService.tryLock(
                    WORKSPACE_ID, QUEUE_ID, ITEM_ID, USER_ALICE, 1, 0, 5))
                    .assertNext(result -> assertThat(result.acquired()).isTrue())
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("getLocksForQueue")
    class GetLocksForQueue {

        @Test
        @DisplayName("should return active locks grouped by item")
        void shouldReturnActiveLocksGroupedByItem() {
            var itemId2 = UUID.randomUUID();
            when(mapReactive.readAllMap()).thenReturn(Mono.just(Map.of(
                    field(ITEM_ID, USER_ALICE), futureExpiry(),
                    field(ITEM_ID, USER_BOB), futureExpiry(),
                    field(itemId2, USER_ALICE), futureExpiry())));

            StepVerifier.create(lockService.getLocksForQueue(WORKSPACE_ID, QUEUE_ID))
                    .assertNext(locks -> {
                        assertThat(locks).hasSize(2);

                        var itemLock = locks.get(ITEM_ID);
                        assertThat(itemLock.activeLocks()).isEqualTo(2);
                        assertThat(itemLock.lockedBy()).containsExactlyInAnyOrder(USER_ALICE, USER_BOB);

                        var item2Lock = locks.get(itemId2);
                        assertThat(item2Lock.activeLocks()).isEqualTo(1);
                        assertThat(item2Lock.lockedBy()).containsExactly(USER_ALICE);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should filter out expired locks")
        void shouldFilterOutExpiredLocks() {
            when(mapReactive.readAllMap()).thenReturn(Mono.just(Map.of(
                    field(ITEM_ID, USER_ALICE), futureExpiry(),
                    field(ITEM_ID, USER_BOB), pastExpiry())));
            when(mapReactive.fastRemove(any(String[].class))).thenReturn(Mono.just(1L));

            StepVerifier.create(lockService.getLocksForQueue(WORKSPACE_ID, QUEUE_ID))
                    .assertNext(locks -> {
                        var itemLock = locks.get(ITEM_ID);
                        assertThat(itemLock.activeLocks()).isEqualTo(1);
                        assertThat(itemLock.lockedBy()).containsExactly(USER_ALICE);
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should clean up expired entries")
        void shouldCleanUpExpiredEntries() {
            var expiredField = field(ITEM_ID, USER_BOB);
            when(mapReactive.readAllMap()).thenReturn(Mono.just(Map.of(
                    expiredField, pastExpiry())));
            when(mapReactive.fastRemove(any(String[].class))).thenReturn(Mono.just(1L));

            StepVerifier.create(lockService.getLocksForQueue(WORKSPACE_ID, QUEUE_ID))
                    .assertNext(locks -> assertThat(locks).isEmpty())
                    .verifyComplete();

            verify(mapReactive).fastRemove(any(String[].class));
        }

        @Test
        @DisplayName("should return empty map when no locks exist")
        void shouldReturnEmptyWhenNoLocks() {
            when(mapReactive.readAllMap()).thenReturn(Mono.just(Map.of()));

            StepVerifier.create(lockService.getLocksForQueue(WORKSPACE_ID, QUEUE_ID))
                    .assertNext(locks -> assertThat(locks).isEmpty())
                    .verifyComplete();
        }
    }
}
