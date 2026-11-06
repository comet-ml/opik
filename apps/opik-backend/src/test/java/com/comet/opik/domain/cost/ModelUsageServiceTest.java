package com.comet.opik.domain.cost;

import com.comet.opik.api.resources.utils.LogCaptureUtils;
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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
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

    @Test
    void aFailedReleaseIsLoggedWithTheClaimsItLeaves() {
        doReturn(bucket).when(redisClient).getBucket(anyString(), any(Codec.class));
        when(bucket.setIfAbsent(any(), any(Duration.class))).thenReturn(Mono.just(true));
        when(bucket.delete()).thenReturn(Mono.error(new IllegalStateException("Redis is down")));
        when(transactionTemplate.inTransaction(any(TxConfig.class), any()))
                .thenThrow(new IllegalStateException("MySQL is down"));

        var logs = LogCaptureUtils.logEventsOf(() -> assertThatCode(
                () -> service.save(ORGANIZATION_LAYERS, "ws-a", "team-a", GPT_5_MINI).block())
                .doesNotThrowAnyException());

        assertThat(logs).anySatisfy(line -> assertThat(line)
                .contains("Failed to give back the claims of the models used, so they are saved again from the next "
                        + "UTC day")
                .contains("organization 'org-1', workspace 'ws-a', claims '1', first models '[openai/gpt-5-mini]'")
                .contains("Redis is down"));
    }

    @Test
    void theLoggedSampleIsCappedShortAndFreeOfControlCharacters() {
        var models = IntStream.range(0, ModelUsageServiceImpl.MAX_LOGGED_MODELS + 2)
                .mapToObj(index -> new UsedModel("model-" + index, "openai"))
                .collect(Collectors.toCollection(ArrayList::new));
        models.set(0, new UsedModel("line\nbreak", "openai"));
        models.set(1, new UsedModel("x".repeat(300), "openai"));

        var sample = ModelUsageServiceImpl.loggedSample(models);

        assertThat(sample).hasSize(ModelUsageServiceImpl.MAX_LOGGED_MODELS);
        assertThat(sample.get(0)).isEqualTo("openai/line?break");
        assertThat(sample.get(1)).isEqualTo("openai/" + "x".repeat(90) + "...");
        assertThat(sample.get(2)).isEqualTo("openai/model-2");
    }

    @Test
    void aFailedClaimGivesBackTheClaimsAlreadyTaken() {
        RBucketReactive<Object> claimedBucket = mock(RBucketReactive.class);
        RBucketReactive<Object> failingBucket = mock(RBucketReactive.class);
        doAnswer(invocation -> invocation.<String>getArgument(0).endsWith("gpt-5-mini")
                ? claimedBucket
                : failingBucket).when(redisClient).getBucket(anyString(), any(Codec.class));
        when(claimedBucket.setIfAbsent(any(), any(Duration.class))).thenReturn(Mono.just(true));
        when(claimedBucket.delete()).thenReturn(Mono.just(true));
        when(failingBucket.setIfAbsent(any(), any(Duration.class)))
                .thenReturn(Mono.error(new IllegalStateException("Redis timed out")));

        service.save(ORGANIZATION_LAYERS, "ws-a", "team-a",
                List.of(new UsedModel("gpt-5-mini", "openai"), new UsedModel("gpt-4o", "openai"))).block();

        verify(claimedBucket).delete();
        verifyNoInteractions(transactionTemplate);
    }

    @Test
    void modelsWhoseNamesShareColonsGetTheirOwnKeys() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        assertThat(ModelUsageServiceImpl.modelKey("org-1", today, "ws-a", new UsedModel("llama", "ollama:local")))
                .isNotEqualTo(ModelUsageServiceImpl.modelKey("org-1", today, "ws-a",
                        new UsedModel("local:llama", "ollama")));
    }
}
