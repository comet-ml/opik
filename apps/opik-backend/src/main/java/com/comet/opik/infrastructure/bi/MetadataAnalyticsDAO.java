package com.comet.opik.infrastructure.bi;

import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.google.inject.ImplementedBy;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.r2dbc.spi.Result;
import jakarta.inject.Named;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.reactivestreams.Publisher;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@ImplementedBy(MetadataAnalyticsDAOImpl.class)
interface MetadataAnalyticsDAO {

    Mono<List<String>> getTablesForDailyReport();

    Mono<Set<String>> getDailyReportUsers(String tableName);

    Mono<Set<String>> getAllTimesReportUsers(String tableName);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class MetadataAnalyticsDAOImpl implements MetadataAnalyticsDAO {

    private static final String DAILY_REPORT_USERS_TABLES = """
            SELECT table
            FROM system.columns
            WHERE database = :database
              AND name IN ('last_updated_at', 'last_updated_by')
            GROUP BY table
            HAVING COUNT(DISTINCT name) = 2
            ;
            """;

    private static final String DAILY_REPORT_USERS = """
            SELECT DISTINCT last_updated_by
            FROM <table_name> FINAL
            <if(daily)>
            WHERE last_updated_at BETWEEN toDateTime64(toStartOfDay(yesterday()), 9) AND toDateTime64(toStartOfDay(NOW64()) - INTERVAL 1 NANOSECOND, 9)
            <endif>
            ;
            """;

    private final @NonNull TransactionTemplateAsync template;
    private final @NonNull @Named("Database Analytics Database Name") String databaseAnalyticsName;

    @Override
    public Mono<List<String>> getTablesForDailyReport() {
        return template.nonTransaction(connection -> Mono.from(connection.createStatement(DAILY_REPORT_USERS_TABLES)
                .bind("database", databaseAnalyticsName)
                .execute()))
                .flatMapMany(result -> result.map((row, metadata) -> row.get("table", String.class)))
                .collectList();
    }

    @Override
    public Mono<Set<String>> getDailyReportUsers(@NonNull String tableName) {
        return fetchUsers(tableName, true)
                .flatMapMany(result -> result.map((row, metadata) -> row.get("last_updated_by", String.class)))
                .collect(Collectors.toSet());
    }

    @Override
    public Mono<Set<String>> getAllTimesReportUsers(@NonNull String tableName) {
        return fetchUsers(tableName, false)
                .flatMapMany(this::mapUsers)
                .collect(Collectors.toSet());
    }

    private Publisher<String> mapUsers(Result result) {
        return result.map((row, metadata) -> row.get("last_updated_by", String.class));
    }

    private Mono<? extends Result> fetchUsers(String tableName, boolean daily) {
        return template.nonTransaction(connection -> {
            ST template = new ST(DAILY_REPORT_USERS);

            template.add("table_name", tableName);
            template.add("daily", daily);

            return Mono.from(connection.createStatement(template.render()).execute());
        });
    }

}