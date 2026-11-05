package com.comet.opik.domain.cost;

import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.redisson.api.RBucketReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.codec.Codec;
import reactor.core.publisher.Mono;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxConfig;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ModelUsageServiceTest {

    private static final List<UsedModel> GPT_5_MINI = List.of(new UsedModel("gpt-5-mini", "openai"));
    private static final PriceLayers ORGANIZATION_LAYERS = PriceLayers.of("org-1", 3, List.of());

    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final RedissonReactiveClient redisClient = mock(RedissonReactiveClient.class);
    @SuppressWarnings("unchecked")
    private final RBucketReactive<Object> bucket = mock(RBucketReactive.class);
    private final ModelUsageServiceImpl service = new ModelUsageServiceImpl(transactionTemplate, redisClient,
            mock(ModelPriceService.class));

    static Stream<Named<PriceLayers>> layersWithoutAnOrganization() {
        return Stream.of(
                Named.of("custom model prices switched off", PriceLayers.BUILT_IN_ONLY),
                Named.of("organization unknown", PriceLayers.NO_ORGANIZATION),
                Named.of("organization's prices failed to load", PriceLayers.LOAD_FAILED));
    }

    @ParameterizedTest
    @MethodSource("layersWithoutAnOrganization")
    void saveWithoutAnOrganizationTouchesNeitherRedisNorMySql(PriceLayers layers) {
        service.save(layers, "ws-a", "team-a", GPT_5_MINI).block();

        verifyNoInteractions(redisClient, transactionTemplate);
    }

    @Test
    void saveOfAModelAlreadyClaimedTodaySkipsMySql() {
        doReturn(bucket).when(redisClient).getBucket(anyString(), any(Codec.class));
        when(bucket.setIfAbsent(any(), any(Duration.class))).thenReturn(Mono.just(false));

        service.save(ORGANIZATION_LAYERS, "ws-a", "team-a", GPT_5_MINI).block();

        verifyNoInteractions(transactionTemplate);
    }

    @Test
    void saveNeverFailsWhenRedisFails() {
        doReturn(bucket).when(redisClient).getBucket(anyString(), any(Codec.class));
        when(bucket.setIfAbsent(any(), any(Duration.class)))
                .thenReturn(Mono.error(new IllegalStateException("Redis is down")));

        assertThatCode(() -> service.save(ORGANIZATION_LAYERS, "ws-a", "team-a", GPT_5_MINI).block())
                .doesNotThrowAnyException();
        verifyNoInteractions(transactionTemplate);
    }

    @Test
    void failedMySqlWriteGivesTheClaimsBack() {
        doReturn(bucket).when(redisClient).getBucket(anyString(), any(Codec.class));
        when(bucket.setIfAbsent(any(), any(Duration.class))).thenReturn(Mono.just(true));
        when(bucket.delete()).thenReturn(Mono.just(true));
        when(transactionTemplate.inTransaction(any(TxConfig.class), any()))
                .thenThrow(new IllegalStateException("MySQL is down"));

        assertThatCode(() -> service.save(ORGANIZATION_LAYERS, "ws-a", "team-a", GPT_5_MINI).block())
                .doesNotThrowAnyException();
        verify(bucket).delete();
    }
}
