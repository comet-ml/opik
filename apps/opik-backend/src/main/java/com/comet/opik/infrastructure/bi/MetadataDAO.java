package com.comet.opik.infrastructure.bi;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.util.List;
import java.util.Optional;
import java.util.Set;

interface MetadataDAO {

    @SqlQuery("SELECT value FROM metadata WHERE `key` = :key")
    Optional<String> getMetadataKey(@Bind("key") String key);

    @SqlUpdate("INSERT INTO metadata (`key`, value) VALUES (:key, :value)")
    void saveMetadataKey(@Bind("key") String key, @Bind("value") String value);

    @SqlQuery("""
                    SELECT
                        CASE
                            WHEN NOT EXISTS (
                                SELECT 1
                                FROM metadata
                                WHERE `key` = 'daily_usage_report'
                            ) THEN TRUE
                            ELSE (
                                SELECT
                                    CASE
                                        WHEN STR_TO_DATE(value, '%Y-%m-%d') IS NULL OR STR_TO_DATE(value, '%Y-%m-%d') < CURDATE() THEN TRUE
                                        ELSE FALSE
                                    END
                                FROM metadata
                                WHERE `key` = 'daily_usage_report'
                            )
                        END AS result
            """)
    boolean shouldSendDailyReport();

    @SqlUpdate("""
                INSERT INTO metadata (`key`, value)
                VALUES ('daily_usage_report', CURDATE())
                ON DUPLICATE KEY UPDATE value = CURDATE()
            """)
    void markDailyReportAsSent();

    @SqlQuery("""
                    SELECT TABLE_NAME
                    FROM INFORMATION_SCHEMA.COLUMNS
                    WHERE TABLE_SCHEMA = :database
                    AND COLUMN_NAME IN ('last_updated_at', 'last_updated_by')
                    GROUP BY TABLE_NAME
                    HAVING COUNT(DISTINCT COLUMN_NAME) = 2
            """)
    List<String> getTablesForDailyReport(@Bind("database") String database);

    @UseStringTemplateEngine
    @SqlQuery("""
                   SELECT DISTINCT last_updated_by
                   FROM <table_name>
                   <if(daily)>
                   WHERE last_updated_at BETWEEN TIMESTAMP(DATE_SUB(CURDATE(), INTERVAL 1 DAY)) AND TIMESTAMP(CURDATE() - INTERVAL 1 MICROSECOND)
                   <endif>
            """)
    Set<String> getReportUsers(@Define("table_name") String tableName, @Define("daily") boolean daily);
}
