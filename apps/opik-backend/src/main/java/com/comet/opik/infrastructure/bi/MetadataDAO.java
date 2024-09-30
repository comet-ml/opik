package com.comet.opik.infrastructure.bi;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.Optional;

interface MetadataDAO {

    @SqlQuery("SELECT value FROM metadata WHERE `key` = :key")
    Optional<String> getMetadataKey(@Bind("key") String key);

    @SqlUpdate("INSERT INTO metadata (`key`, value) VALUES (:key, :value)")
    void saveMetadataKey(@Bind("key") String key, @Bind("value") String value);
}
