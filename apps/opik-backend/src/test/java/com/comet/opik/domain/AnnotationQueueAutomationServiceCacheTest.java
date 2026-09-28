package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue.AnnotationScope;
import com.comet.opik.api.AnnotationQueueAutomation;
import com.comet.opik.api.annotationqueue.ConditionGroup;
import com.comet.opik.api.annotationqueue.Conditions;
import com.comet.opik.api.annotationqueue.ScoreCondition;
import com.comet.opik.api.annotationqueue.ScoreConditionOperator;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.CustomConfig;
import com.comet.opik.domain.evaluators.AutomationRuleAnnotationQueueRouterDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards that the {@code annotation_queue_automations} cache on
 * {@link AnnotationQueueAutomationService#hasEnabledAutomation(String, UUID, AnnotationScope)} engages and
 * that the writes evict it. The method is read once per feedback score event, so losing the cache is a
 * database round trip on the busiest event in the system; losing the eviction is worse, because a stale
 * {@code false} is silent — the events it turns away are dropped at the guard and there is no backfill.
 *
 * <p>The TTL here is deliberately far longer than any test takes. A short one would let expiry stand in for
 * eviction, and the eviction tests would pass with the {@code @CacheEvict} annotations deleted. The waits
 * below are bounded well under it for the same reason.
 *
 * <p>Eviction is awaited rather than asserted outright, because {@code CacheInterceptor} evicts a
 * synchronous method's entry through {@code evictAsync} without waiting for it - its own words, "makes
 * cache eventual consistent". So a rule change is visible to the guard very soon, not instantly, and a
 * test demanding instantly would be asserting something the infrastructure does not offer.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class AnnotationQueueAutomationServiceCacheTest {

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER_CONTAINER);

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(MYSQL, CLICKHOUSE, REDIS, ZOOKEEPER_CONTAINER).join();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICKHOUSE, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(REDIS.getRedisURI())
                        .customConfigs(List.of(
                                new CustomConfig("cacheManager.enabled", "true"),
                                new CustomConfig("cacheManager.caches.annotation_queue_automations",
                                        "PT%dS".formatted(CACHE_TTL.toSeconds()))))
                        .build());
    }

    @Test
    void hasEnabledAutomation__creatingAnAutomationEvictsTheCachedNegative(
            AnnotationQueueAutomationService service) {

        var fixture = new Fixture();

        // The negative is the answer worth caching: most workspaces have no automation at all.
        assertThat(service.hasEnabledAutomation(fixture.workspaceId, fixture.projectId, AnnotationScope.TRACE))
                .isFalse();

        fixture.save(service, true);

        // With a ten minute TTL, only the eviction can explain the answer changing inside this window.
        awaitAnswer(service, fixture.workspaceId, fixture.projectId, true);
    }

    @Test
    void hasEnabledAutomation__deletingAnAutomationEvictsTheCachedPositive(
            AnnotationQueueAutomationService service) {

        var fixture = new Fixture();
        fixture.save(service, true);

        assertThat(service.hasEnabledAutomation(fixture.workspaceId, fixture.projectId, AnnotationScope.TRACE))
                .isTrue();

        service.deleteByQueueIds(fixture.workspaceId, List.of(fixture.queueId));

        awaitAnswer(service, fixture.workspaceId, fixture.projectId, false);
    }

    @Test
    void hasEnabledAutomation__theWorkspaceWideFormIsCachedSeparatelyAndEvictedToo(
            AnnotationQueueAutomationService service) {

        var fixture = new Fixture();

        // A null project id is the batch score path. It keys on an empty project id, so it must not collide
        // with the project-scoped entry above, and the pattern eviction has to clear both.
        assertThat(service.hasEnabledAutomation(fixture.workspaceId, null, AnnotationScope.TRACE)).isFalse();
        assertThat(service.hasEnabledAutomation(fixture.workspaceId, fixture.projectId, AnnotationScope.TRACE))
                .isFalse();

        fixture.save(service, true);

        // Both keys, from the one pattern eviction.
        awaitAnswer(service, fixture.workspaceId, null, true);
        awaitAnswer(service, fixture.workspaceId, fixture.projectId, true);
    }

    /**
     * Proves the answer is served from the cache at all, rather than the eviction tests merely observing a
     * fast database. The rule is removed underneath the service, by the DAO the service itself uses, so
     * nothing evicts — and the stale answer must survive.
     */
    @Test
    void hasEnabledAutomation__anAnswerIsServedFromTheCacheWhenNothingEvicts(
            AnnotationQueueAutomationService service, TransactionTemplate transactionTemplate) {

        var fixture = new Fixture();
        fixture.save(service, true);

        assertThat(service.hasEnabledAutomation(fixture.workspaceId, fixture.projectId, AnnotationScope.TRACE))
                .isTrue();

        List<UUID> ruleIds = transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AutomationRuleAnnotationQueueRouterDAO.class);
            var ids = dao.findRuleIdsByQueueIds(fixture.workspaceId, List.of(fixture.queueId));
            dao.deleteByRuleIds(ids);
            return ids;
        });
        assertThat(ruleIds).isNotEmpty();

        assertThat(service.hasEnabledAutomation(fixture.workspaceId, fixture.projectId, AnnotationScope.TRACE))
                .as("the pre-eviction answer must still be served, or the cache is not engaging at all")
                .isTrue();
    }

    private static void awaitAnswer(AnnotationQueueAutomationService service, String workspaceId,
            UUID projectId, boolean expected) {
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(50))
                .untilAsserted(() -> assertThat(
                        service.hasEnabledAutomation(workspaceId, projectId, AnnotationScope.TRACE))
                        .isEqualTo(expected));
    }

    /** A workspace of its own per test, so one test's cached answers can never be read by another. */
    private static final class Fixture {
        private final String workspaceId = UUID.randomUUID().toString();
        private final String userName = RandomStringUtils.secure().nextAlphanumeric(20);
        private final String queueName = RandomStringUtils.secure().nextAlphanumeric(20);
        private final UUID queueId = UUID.randomUUID();
        private final UUID projectId = UUID.randomUUID();

        private void save(AnnotationQueueAutomationService service, boolean enabled) {
            service.save(workspaceId, userName, queueId, projectId, AnnotationScope.TRACE, queueName,
                    AnnotationQueueAutomation.builder()
                            .enabled(enabled)
                            // An enabled automation is rejected without at least one group.
                            .conditions(Conditions.builder()
                                    .groups(List.of(ConditionGroup.builder()
                                            .conditions(List.of(ScoreCondition.builder()
                                                    .scoreName(RandomStringUtils.secure().nextAlphanumeric(10))
                                                    .operator(ScoreConditionOperator.GREATER_THAN)
                                                    .value(0.5)
                                                    .build()))
                                            .build()))
                                    .build())
                            .build());
        }
    }
}
