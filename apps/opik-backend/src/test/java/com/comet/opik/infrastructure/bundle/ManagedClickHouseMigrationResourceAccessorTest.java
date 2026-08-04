package com.comet.opik.infrastructure.bundle;

import liquibase.Scope;
import liquibase.resource.ClassLoaderResourceAccessor;
import liquibase.resource.Resource;
import liquibase.resource.ResourceAccessor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.bundle.LiquibaseBundle.DB_APP_ANALYTICS_MIGRATIONS_FILE_NAME;
import static com.comet.opik.infrastructure.bundle.LiquibaseBundle.DB_APP_ANALYTICS_NAME;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIOException;

class ManagedClickHouseMigrationResourceAccessorTest {

    private static final String ANALYTICS_MIGRATIONS_PATH = "liquibase/db-app-analytics/migrations";

    @Test
    void transformsReplicatedEngineArgumentsAndPreservesOtherSql() throws IOException {
        var sql = """
                --liquibase formatted sql
                --changeset test:managed-clickhouse
                CREATE TABLE events (id UUID)
                ENGINE = ReplicatedMergeTree('/clickhouse/tables/{shard}/opik/events', '{replica}')
                ORDER BY id;

                CREATE TABLE versions (id UUID, last_updated_at DateTime64(6))
                ENGINE = ReplicatedReplacingMergeTree(
                    '/clickhouse/tables/{shard}/opik/versions',
                    '{replica}',
                    last_updated_at
                )
                ORDER BY (tuple());
                """;

        assertThat(ManagedClickHouseMigrationResourceAccessor.transform("test.sql", sql)).isEqualTo("""
                --liquibase formatted sql
                --changeset test:managed-clickhouse
                CREATE TABLE events (id UUID)
                ENGINE = ReplicatedMergeTree()
                ORDER BY id;

                CREATE TABLE versions (id UUID, last_updated_at DateTime64(6))
                ENGINE = ReplicatedReplacingMergeTree(last_updated_at)
                ORDER BY (tuple());
                """);
    }

    @Test
    void leavesAlreadyManagedEngineArgumentsUnchanged() throws IOException {
        var sql = """
                ENGINE = ReplicatedMergeTree()
                ENGINE = ReplicatedReplacingMergeTree(last_updated_at)
                """;

        assertThat(ManagedClickHouseMigrationResourceAccessor.transform("test.sql", sql)).isEqualTo(sql);
    }

    @Test
    void transformsEveryAnalyticsMigrationWithoutChangingUnrelatedResources() throws Exception {
        try (var managedAccessor = new ManagedClickHouseMigrationResourceAccessor();
                var originalAccessor = ManagedClickHouseMigrationResourceAccessor.create(false)) {
            Map<String, String> originals = originalAccessor.search(ANALYTICS_MIGRATIONS_PATH, true).stream()
                    .filter(resource -> resource.getPath().endsWith(".sql"))
                    .collect(Collectors.toMap(Resource::getPath, ManagedClickHouseMigrationResourceAccessorTest::read));
            Map<String, String> transformed = managedAccessor.search(ANALYTICS_MIGRATIONS_PATH, true).stream()
                    .filter(resource -> resource.getPath().endsWith(".sql"))
                    .collect(Collectors.toMap(Resource::getPath, ManagedClickHouseMigrationResourceAccessorTest::read));

            assertThat(transformed).hasSameSizeAs(originals);
            assertThat(transformed.values())
                    .noneMatch(sql -> sql.contains("/clickhouse/tables/{shard}"))
                    .noneMatch(sql -> sql.contains("'{replica}'"));
            originals.forEach((path, original) -> {
                if (!original.contains("ENGINE = Replicated")) {
                    assertThat(transformed.get(path)).as(path).isEqualTo(original);
                }
            });
        }
    }

    @Test
    void leavesStateDatabaseResourcesUnchanged() throws Exception {
        var stateMigration = "liquibase/db-app-state/changelog.xml";
        try (var managedAccessor = new ManagedClickHouseMigrationResourceAccessor()) {
            var original = getClass().getClassLoader().getResourceAsStream(stateMigration);

            assertThat(original).isNotNull();
            assertThat(read(managedAccessor.getExisting(stateMigration))).isEqualTo(read(original));
        }
    }

    @Test
    void rejectsUnrecognizedReplicatedEngineArgumentsWithResourceName() {
        var sql = "ENGINE = ReplicatedMergeTree('/custom/path', '{replica}')";

        assertThatIOException()
                .isThrownBy(() -> ManagedClickHouseMigrationResourceAccessor.transform("unsafe.sql", sql))
                .withMessageContaining("unsafe.sql")
                .withMessageContaining("ReplicatedMergeTree");
    }

    @Test
    void bundleUsesStandardClasspathAccessorUnlessManagedStrategyIsSelected() {
        var defaultBundle = LiquibaseBundle.builder()
                .name(DB_APP_ANALYTICS_NAME)
                .migrationsFileName(DB_APP_ANALYTICS_MIGRATIONS_FILE_NAME)
                .dataSourceFactoryFunction(configuration -> null)
                .build();
        var managedBundle = newAnalyticsBundle(ManagedClickHouseMigrationResourceAccessor::new);

        assertThat(defaultBundle.getScopedObjects().get(Scope.Attr.resourceAccessor.name()))
                .isExactlyInstanceOf(ClassLoaderResourceAccessor.class);
        assertThat(managedBundle.getScopedObjects().get(Scope.Attr.resourceAccessor.name()))
                .isExactlyInstanceOf(ManagedClickHouseMigrationResourceAccessor.class);
    }

    @Test
    void environmentStrategyIsOptIn() {
        assertThat(ManagedClickHouseMigrationResourceAccessor.create(false))
                .isExactlyInstanceOf(ClassLoaderResourceAccessor.class);
        assertThat(ManagedClickHouseMigrationResourceAccessor.create(true))
                .isExactlyInstanceOf(ManagedClickHouseMigrationResourceAccessor.class);
    }

    private LiquibaseBundle newAnalyticsBundle(Supplier<ResourceAccessor> resourceAccessorSupplier) {
        return LiquibaseBundle.builder()
                .name(DB_APP_ANALYTICS_NAME)
                .migrationsFileName(DB_APP_ANALYTICS_MIGRATIONS_FILE_NAME)
                .dataSourceFactoryFunction(configuration -> null)
                .resourceAccessorSupplier(resourceAccessorSupplier)
                .build();
    }

    private static String read(Resource resource) {
        try (var inputStream = resource.openInputStream()) {
            return read(inputStream);
        } catch (IOException exception) {
            throw new RuntimeException(exception);
        }
    }

    private static String read(InputStream inputStream) {
        try (inputStream) {
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new RuntimeException(exception);
        }
    }
}
