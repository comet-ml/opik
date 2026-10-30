package com.comet.opik.domain;

import com.comet.opik.api.DeploymentVersion;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;

/**
 * Append-only log of the versions this install has run (table {@code deployment_version_history}).
 * <p>
 * Not workspace-scoped: it describes the deployment, not any tenant within it.
 */
@RegisterConstructorMapper(DeploymentVersion.class)
interface DeploymentVersionDAO {

    /**
     * Records the running version, if this install has not run it before.
     * <p>
     * INSERT IGNORE against the {@code version} primary key is what makes this safe to call from every
     * replica on every boot: concurrent inserts of the same version collapse into a single row, and the
     * surviving row keeps the {@code first_seen_at} of the first replica to win the race.
     *
     * @return 1 if this is the first time the install has run this version, 0 if it was already recorded
     */
    @SqlUpdate("INSERT IGNORE INTO deployment_version_history (version) VALUES (:version)")
    int recordVersion(@Bind("version") String version);

    @SqlQuery("SELECT version, first_seen_at FROM deployment_version_history ORDER BY first_seen_at DESC, version DESC")
    List<DeploymentVersion> findAll();
}
