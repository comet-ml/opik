package com.comet.opik.domain;

import com.comet.opik.api.LockResponse;
import com.comet.opik.infrastructure.lock.LockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RMapReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.codec.Codec;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnnotationQueueItemLockServiceTest {

    private static final String WORKSPACE_ID = "test-workspace";
    private static final UUID QUEUE_ID = UUID.randomUUID();
    private static final UUID ITEM_ID = UUID.randomUUID();
    private static final String USER_ALICE = "alice";
    private static final String USER_BOB = "bob";
    private static final String PERMIT_ID = "permit-123";

    @Mock
    private RedissonReactiveClient redisClient;
    @Mock
    private LockService lockService;
    @Mock
    private RMapReactive<String, String> mapCache;

    private AnnotationQueueItemLockServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AnnotationQueueItemLockServiceImpl(redisClient, lockService);
        lenient().when(redisClient.<String, String>getMap(anyString(), any(Codec.class)))
                .thenReturn(mapCache);
        lenient().when(mapCache.expire(any(Duration.class))).thenReturn(Mono.just(true));
    }

    private String field(UUID itemId, String userName) {
        return itemId + ":" + userName;
    }

    private String packValue(String permitId, long expiryMs) {
        return permitId + ":" + expiryMs;
    }

    private long futureExpiry() {
        return System.currentTimeMillis() + 60_000;
    }

    private long pastExpiry() {
        return System.currentTimeMillis() - 1000;
    }

    @Nested
    @DisplayName("tryLock")
    class TryLock {

        @Test
        @DisplayName("should return denied when scoredCount >= annotatorsPerItem and user has no lock")
        void shouldDenyWhenFullyScored() {
            when(mapCache.get(anyString())).thenReturn(Mono.empty());

            StepVerifier.create(service.tryLock(
                    WORKSPACE_ID, QUEUE_ID, ITEM_ID, USER_ALICE, 2, 2, 5))
                    .assertNext(result -> assertThat(result.acquired()).isFalse())
                    .verifyComplete();

            verify(lockService, never()).tryAcquireSlot(any(), anyInt(), any());
        }

        @Test
        @DisplayName("should allow heartbeat when user already scored but still holds a valid lock")
        void shouldAllowHeartbeatWhenUserAlreadyScoredButHoldsLock() {
            when(mapCache.get(field(ITEM_ID, USER_ALICE)))
                    .thenReturn(Mono.just(packValue(PERMIT_ID, futureExpiry())));
            when(lockService.refreshSlot(any(), eq(PERMIT_ID), any())).thenReturn(Mono.just(true));
            when(mapCache.fastPut(anyString(), anyString()))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(service.tryLock(
                    WORKSPACE_ID, QUEUE_ID, ITEM_ID, USER_ALICE, 1, 1, 5))
                    .assertNext(result -> assertThat(result.acquired()).isTrue())
                    .verifyComplete();

            verify(lockService).refreshSlot(any(), eq(PERMIT_ID), any());
            verify(lockService, never()).tryAcquireSlot(any(), anyInt(), any());
        }

        @Test
        @DisplayName("should acquire lock via semaphore when no existing permit")
        void shouldAcquireWhenEmpty() {
            when(mapCache.get(anyString())).thenReturn(Mono.empty());
            when(lockService.tryAcquireSlot(any(), eq(1), any())).thenReturn(Mono.just(PERMIT_ID));
            when(mapCache.fastPutIfAbsent(anyString(), anyString()))
                    .thenReturn(Mono.just(true));

            var expected = LockResponse.builder()
                    .acquired(true)
                    .itemId(ITEM_ID)
                    .lockedBy(USER_ALICE)
                    .build();

            StepVerifier.create(service.tryLock(
                    WORKSPACE_ID, QUEUE_ID, ITEM_ID, USER_ALICE, 1, 0, 5))
                    .assertNext(result -> {
                        assertThat(result)
                                .usingRecursiveComparison()
                                .ignoringFields("expiresAt")
                                .isEqualTo(expected);
                        assertThat(result.expiresAt()).isNotNull();
                    })
                    .verifyComplete();
        }

        @Test
        @DisplayName("should refresh lease when user already holds a permit (heartbeat)")
        void shouldRefreshWhenUserAlreadyHoldsPermit() {
            when(mapCache.get(field(ITEM_ID, USER_ALICE)))
                    .thenReturn(Mono.just(packValue(PERMIT_ID, futureExpiry())));
            when(lockService.refreshSlot(any(), eq(PERMIT_ID), any())).thenReturn(Mono.just(true));
            when(mapCache.fastPut(anyString(), anyString()))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(service.tryLock(
                    WORKSPACE_ID, QUEUE_ID, ITEM_ID, USER_ALICE, 1, 0, 5))
                    .assertNext(result -> assertThat(result.acquired()).isTrue())
                    .verifyComplete();

            verify(lockService, never()).tryAcquireSlot(any(), anyInt(), any());
        }

        @Test
        @DisplayName("should fall through to acquire when existing permit expired")
        void shouldFallThroughWhenPermitExpired() {
            when(mapCache.get(field(ITEM_ID, USER_ALICE)))
                    .thenReturn(Mono.just(packValue(PERMIT_ID, pastExpiry())));

            when(lockService.tryAcquireSlot(any(), eq(1), any())).thenReturn(Mono.just("new-permit"));
            when(mapCache.fastPutIfAbsent(anyString(), anyString()))
                    .thenReturn(Mono.just(true));

            StepVerifier.create(service.tryLock(
                    WORKSPACE_ID, QUEUE_ID, ITEM_ID, USER_ALICE, 1, 0, 5))
                    .assertNext(result -> assertThat(result.acquired()).isTrue())
                    .verifyComplete();
        }

        @Test
        @DisplayName("should deny lock when semaphore has no permits available")
        void shouldDenyWhenNoPermitsAvailable() {
            when(mapCache.get(anyString())).thenReturn(Mono.empty());
            when(lockService.tryAcquireSlot(any(), eq(1), any())).thenReturn(Mono.empty());

            var expected = LockResponse.builder()
                    .acquired(false)
                    .itemId(ITEM_ID)
                    .lockedBy(USER_ALICE)
                    .expiresAt(null)
                    .build();

            StepVerifier.create(service.tryLock(
                    WORKSPACE_ID, QUEUE_ID, ITEM_ID, USER_ALICE, 1, 0, 5))
                    .assertNext(result -> assertThat(result)
                            .usingRecursiveComparison()
                            .isEqualTo(expected))
                    .verifyComplete();
        }

        @Test
        @DisplayName("should pass effectiveCapacity = annotatorsPerItem - scoredCount")
        void shouldAccountForScoredCount() {
            when(mapCache.get(anyString())).thenReturn(Mono.empty());
            when(lockService.tryAcquireSlot(any(), eq(1), any())).thenReturn(Mono.empty());

            StepVerifier.create(service.tryLock(
                    WORKSPACE_ID, QUEUE_ID, ITEM_ID, USER_ALICE, 3, 2, 5))
                    .assertNext(result -> assertThat(result.acquired()).isFalse())
                    .verifyComplete();

            verify(lockService).tryAcquireSlot(any(), eq(1), any());
        }

        @Test
        @DisplayName("should release duplicate permit on race condition")
        void shouldReleaseDuplicateOnRace() {
            when(mapCache.get(anyString())).thenReturn(Mono.empty());
            when(lockService.tryAcquireSlot(any(), eq(1), any())).thenReturn(Mono.just(PERMIT_ID));
            when(mapCache.fastPutIfAbsent(anyString(), anyString()))
                    .thenReturn(Mono.just(false));
            when(lockService.releaseSlot(any(), eq(PERMIT_ID))).thenReturn(Mono.just(true));

            StepVerifier.create(service.tryLock(
                    WORKSPACE_ID, QUEUE_ID, ITEM_ID, USER_ALICE, 1, 0, 5))
                    .assertNext(result -> assertThat(result.acquired()).isTrue())
                    .verifyComplete();

            verify(lockService).releaseSlot(any(), eq(PERMIT_ID));
        }
    }

    @Nested
    @DisplayName("getLocksForQueue")
    class GetLocksForQueue {

        @Test
        @DisplayName("should return active locks grouped by item, filtering expired")
        void shouldReturnActiveLocksGroupedByItem() {
            var itemId2 = UUID.randomUUID();
            long future = futureExpiry();
            long past = pastExpiry();
            when(mapCache.readAllMap()).thenReturn(Mono.just(Map.of(
                    field(ITEM_ID, USER_ALICE), packValue(PERMIT_ID, future),
                    field(ITEM_ID, USER_BOB), packValue("permit-456", future),
                    field(itemId2, USER_ALICE), packValue("permit-789", past))));
            when(mapCache.fastRemove(any(String[].class))).thenReturn(Mono.just(1L));

            StepVerifier.create(service.getLocksForQueue(WORKSPACE_ID, QUEUE_ID))
                    .assertNext(locks -> {
                        assertThat(locks).hasSize(1);

                        var itemLock = locks.get(ITEM_ID);
                        assertThat(itemLock.activeLocks()).isEqualTo(2);
                        assertThat(itemLock.lockedBy()).containsExactlyInAnyOrder(USER_ALICE, USER_BOB);

                        assertThat(locks.get(itemId2)).isNull();
                    })
                    .verifyComplete();

            verify(mapCache).fastRemove(any(String[].class));
        }

        @Test
        @DisplayName("should return empty map when no locks exist")
        void shouldReturnEmptyWhenNoLocks() {
            when(mapCache.readAllMap()).thenReturn(Mono.just(Map.of()));

            StepVerifier.create(service.getLocksForQueue(WORKSPACE_ID, QUEUE_ID))
                    .assertNext(locks -> assertThat(locks).isEmpty())
                    .verifyComplete();
        }
    }

    @Nested
    @DisplayName("updateCapacity")
    class UpdateCapacity {

        @Test
        @DisplayName("should do nothing when delta is zero")
        void shouldDoNothingWhenDeltaZero() {
            StepVerifier.create(service.updateCapacity(WORKSPACE_ID, QUEUE_ID, 0))
                    .verifyComplete();

            verify(lockService, never()).addSlotPermits(any(), anyInt());
        }

        @Test
        @DisplayName("should call addSlotPermits for each active item, skipping expired")
        void shouldUpdatePermitsForEachItem() {
            var itemId2 = UUID.randomUUID();
            long future = futureExpiry();
            long past = pastExpiry();
            when(mapCache.readAllMap()).thenReturn(Mono.just(Map.of(
                    field(ITEM_ID, USER_ALICE), packValue(PERMIT_ID, future),
                    field(ITEM_ID, USER_BOB), packValue("permit-456", future),
                    field(itemId2, USER_ALICE), packValue("permit-789", past))));
            when(lockService.addSlotPermits(any(), eq(1))).thenReturn(Mono.empty());

            StepVerifier.create(service.updateCapacity(WORKSPACE_ID, QUEUE_ID, 1))
                    .verifyComplete();

            // Only 1 distinct item with active locks (itemId2 is expired)
            verify(lockService, times(1)).addSlotPermits(any(), eq(1));
        }

        @Test
        @DisplayName("should handle negative delta")
        void shouldHandleNegativeDelta() {
            when(mapCache.readAllMap()).thenReturn(Mono.just(Map.of(
                    field(ITEM_ID, USER_ALICE), packValue(PERMIT_ID, futureExpiry()))));
            when(lockService.addSlotPermits(any(), eq(-1))).thenReturn(Mono.empty());

            StepVerifier.create(service.updateCapacity(WORKSPACE_ID, QUEUE_ID, -1))
                    .verifyComplete();

            verify(lockService).addSlotPermits(any(), eq(-1));
        }

        @Test
        @DisplayName("should do nothing when no active locks")
        void shouldDoNothingWhenNoActiveLocks() {
            when(mapCache.readAllMap()).thenReturn(Mono.just(Map.of()));

            StepVerifier.create(service.updateCapacity(WORKSPACE_ID, QUEUE_ID, 1))
                    .verifyComplete();

            verify(lockService, never()).addSlotPermits(any(), anyInt());
        }
    }
}
