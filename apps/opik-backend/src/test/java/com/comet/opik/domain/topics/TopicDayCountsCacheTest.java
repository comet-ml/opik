package com.comet.opik.domain.topics;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.topics.TraceTopicsDAO.TopicDayCount;
import com.comet.opik.infrastructure.CacheConfiguration;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.cache.CacheInterceptor;
import com.comet.opik.infrastructure.cache.CacheManager;
import com.comet.opik.infrastructure.cache.Cacheable;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.inject.AbstractModule;
import com.google.inject.Guice;
import com.google.inject.matcher.Matchers;
import io.dropwizard.configuration.FileConfigurationSourceProvider;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.configuration.YamlConfigurationFactory;
import io.dropwizard.jackson.Jackson;
import lombok.NonNull;
import org.apache.commons.text.StringSubstitutor;
import org.apache.commons.text.lookup.StringLookupFactory;
import org.jdbi.v3.core.Handle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.ConfigImpl;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import ru.vyarus.guicey.jdbi3.tx.TxAction;
import ru.vyarus.guicey.jdbi3.tx.TxConfig;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The counts cache of a ready read as the app wires it: the service made by Guice with the cache interceptor, the
 * {@code cacheManager} section of the shipped config.yml, and a cache that keeps JSON as Redis does. The read goes
 * through {@link TopicReadService#get}, so the test also proves that Guice intercepts the service's own call.
 */
@DisplayName("topics day counts cache")
class TopicDayCountsCacheTest {

    private static final String WORKSPACE_ID = UUID.randomUUID().toString();

    private final UUID projectId = UUID.randomUUID();
    private final TopicsConfig topicsConfig = TestConfigUtils.loadConfigTest().getTopics();

    private InMemoryCache cache;
    private TopicDAO topicDAO;
    private TraceTopicsDAO traceTopicsDAO;
    private Topic topic;
    private TopicReadService service;

    @BeforeEach
    void setUp() throws Exception {
        cache = new InMemoryCache();
        topicDAO = mock(TopicDAO.class);
        traceTopicsDAO = mock(TraceTopicsDAO.class);
        var handle = mock(Handle.class);
        when(handle.attach(TopicDAO.class)).thenReturn(topicDAO);
        var template = mock(TransactionTemplate.class);
        when(template.inTransaction(any(TxConfig.class), any()))
                .thenAnswer(invocation -> invocation.<TxAction<?>>getArgument(1).execute(handle));
        var workspaces = mock(TopicWorkspaceService.class);
        when(workspaces.isEligible(WORKSPACE_ID)).thenReturn(true);
        topic = Topic.builder()
                .id(UUID.randomUUID())
                .workspaceId(WORKSPACE_ID)
                .projectId(projectId)
                .name("Topic A")
                .definition("Definition A")
                .born(LocalDate.now(ZoneOffset.UTC).minusDays(10))
                .build();
        when(topicDAO.findLive(WORKSPACE_ID, projectId)).thenReturn(List.of(topic));
        savedBy(UUID.randomUUID());
        var cacheConfiguration = shipped(Map.of());
        service = Guice.createInjector(new AbstractModule() {
            @Override
            protected void configure() {
                bind(TopicsConfig.class).annotatedWith(new ConfigImpl("topics")).toInstance(topicsConfig);
                bind(TransactionTemplate.class).toInstance(template);
                bind(TopicWorkspaceService.class).toInstance(workspaces);
                bind(ProjectService.class).toInstance(mock(ProjectService.class));
                bind(TopicWindowDAO.class).toInstance(mock(TopicWindowDAO.class));
                bind(TraceTopicsDAO.class).toInstance(traceTopicsDAO);
                bind(TopicsMetrics.class).toInstance(new TopicsMetrics(new TopicsTestMeter().meter));
                bind(CacheManager.class).toInstance(cache);
                bindInterceptor(Matchers.any(), Matchers.annotatedWith(Cacheable.class),
                        new CacheInterceptor(getProvider(CacheManager.class), cacheConfiguration));
            }
        }).getInstance(TopicReadService.class);
    }

    @Test
    @DisplayName("config.yml keeps the counts for 5 minutes by default, and the TTL has its own env var")
    void ttl() throws Exception {
        var shipped = shipped(Map.of());
        assertThat(shipped.isEnabled()).isTrue();
        assertThat(shipped.getCaches().get(TopicReadService.DAY_COUNTS_CACHE)).isEqualTo(Duration.ofMinutes(5));

        var set = shipped(Map.of("CACHE_MANAGER_TOPICS_DAY_COUNTS_DURATION", "PT2M"));
        assertThat(set.getCaches().get(TopicReadService.DAY_COUNTS_CACHE)).isEqualTo(Duration.ofMinutes(2));
    }

    @Test
    @DisplayName("two reads of one project call ClickHouse once; the second gets the same counts from the cache, "
            + "written with the TTL")
    void secondReadIsCached() {
        countsReturn(Flux.just(count(4)));

        var first = service.get(WORKSPACE_ID, projectId);
        var second = service.get(WORKSPACE_ID, projectId);

        verify(traceTopicsDAO, times(1)).countByTopicAndDay(eq(WORKSPACE_ID), eq(projectId), any(), any(), anyLong());
        assertThat(second).isEqualTo(first);
        assertThat(second.totalTraces()).isEqualTo(4L);
        assertThat(cache.ttls.values()).containsExactly(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("a read after a new last_run_id calls ClickHouse again: a saved run never leaves older counts served")
    void newRunReadsAgain() {
        countsReturn(Flux.just(count(4)), Flux.just(count(9)));

        assertThat(service.get(WORKSPACE_ID, projectId).totalTraces()).isEqualTo(4L);
        savedBy(UUID.randomUUID());
        assertThat(service.get(WORKSPACE_ID, projectId).totalTraces()).isEqualTo(9L);

        verify(traceTopicsDAO, times(2)).countByTopicAndDay(eq(WORKSPACE_ID), eq(projectId), any(), any(), anyLong());
    }

    @Test
    @DisplayName("a failed read is not cached: the next read calls ClickHouse again")
    void failedReadIsNotCached() {
        countsReturn(Flux.error(new IllegalStateException("read failed")), Flux.just(count(4)));

        assertThatThrownBy(() -> service.get(WORKSPACE_ID, projectId)).isInstanceOf(IllegalStateException.class);
        assertThat(cache.values).isEmpty();
        assertThat(service.get(WORKSPACE_ID, projectId).totalTraces()).isEqualTo(4L);

        verify(traceTopicsDAO, times(2)).countByTopicAndDay(eq(WORKSPACE_ID), eq(projectId), any(), any(), anyLong());
    }

    @SafeVarargs
    private void countsReturn(Flux<TopicDayCount> first, Flux<TopicDayCount>... next) {
        when(traceTopicsDAO.countByTopicAndDay(eq(WORKSPACE_ID), eq(projectId), any(), any(), anyLong()))
                .thenReturn(first, next);
    }

    private TopicDayCount count(long traces) {
        return TopicDayCount.builder().topicId(topic.id()).day(LocalDate.now(ZoneOffset.UTC)).traces(traces).build();
    }

    private void savedBy(UUID lastRunId) {
        when(topicDAO.findStates(WORKSPACE_ID, List.of(projectId))).thenReturn(List.of(TopicProjectState.builder()
                .projectId(projectId)
                .workspaceId(WORKSPACE_ID)
                .rebuilds(1)
                .firstBuiltAt(Instant.now().minus(Duration.ofDays(20)))
                .lastRunId(lastRunId)
                .lastSuccessAt(Instant.now())
                .build()));
    }

    /** The {@code cacheManager} section of the shipped config.yml, with {@code environment} for the env vars. */
    private static CacheConfiguration shipped(Map<String, String> environment) throws Exception {
        var substitutor = new StringSubstitutor(StringLookupFactory.INSTANCE.mapStringLookup(environment));
        substitutor.setValueDelimiter(":-");
        substitutor.setEnableUndefinedVariableException(false);
        return new YamlConfigurationFactory<>(OpikConfiguration.class, null, Jackson.newObjectMapper(), "dw")
                .build(new SubstitutingSourceProvider(new FileConfigurationSourceProvider(), substitutor),
                        Path.of("config.yml").toString())
                .getCacheManager();
    }

    /** Keeps each value as the JSON Redis would hold, with the TTL it was written with. */
    private static final class InMemoryCache implements CacheManager {

        final Map<String, String> values = new ConcurrentHashMap<>();
        final Map<String, Duration> ttls = new ConcurrentHashMap<>();

        @Override
        public Mono<Boolean> put(@NonNull String key, @NonNull Object value, @NonNull Duration ttlDuration) {
            values.put(key, JsonUtils.writeValueAsString(value));
            ttls.put(key, ttlDuration);
            return Mono.just(true);
        }

        @Override
        public <T> Mono<T> get(@NonNull String key, @NonNull TypeReference<T> typeReference) {
            return Mono.justOrEmpty(values.get(key)).map(json -> JsonUtils.readValue(json, typeReference));
        }

        @Override
        public <T> Mono<T> get(@NonNull String key, @NonNull Class<T> clazz) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<Boolean> evict(@NonNull String key, boolean usePatternMatching) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Mono<Boolean> contains(@NonNull String key) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Boolean> evictAsync(String key, boolean usePatternMatching) {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletionStage<Void> putAsync(String key, Object value, Duration ttlDuration) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean putIfAbsentSync(String key, Object value, Duration ttlDuration) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T getSync(String key, Class<T> clazz) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> T getSync(String key, TypeReference<T> clazz) {
            throw new UnsupportedOperationException();
        }
    }
}
