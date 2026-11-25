--liquibase formatted sql
--changeset yaroslavb:000123_rename_topic_project_run_modes_widen
--comment: topic_projects.run_mode: allow the new names next to the old ones, so the rows can move over (OPIK-8987)
ALTER TABLE topic_projects
    MODIFY COLUMN run_mode ENUM ('first_build', 'nightly_update', 'discovery', 'update') NULL;

--rollback ALTER TABLE topic_projects MODIFY COLUMN run_mode ENUM ('first_build', 'nightly_update') NULL;

--changeset yaroslavb:000123_rename_topic_project_run_modes_move
--comment: topic_projects.run_mode: first_build becomes discovery, nightly_update becomes update (OPIK-8987)
UPDATE topic_projects SET run_mode = 'discovery' WHERE run_mode = 'first_build';
UPDATE topic_projects SET run_mode = 'update' WHERE run_mode = 'nightly_update';

--rollback UPDATE topic_projects SET run_mode = 'first_build' WHERE run_mode = 'discovery';
--rollback UPDATE topic_projects SET run_mode = 'nightly_update' WHERE run_mode = 'update';

--changeset yaroslavb:000123_rename_topic_project_run_modes_narrow
--comment: topic_projects.run_mode: only the new names (OPIK-8987)
ALTER TABLE topic_projects
    MODIFY COLUMN run_mode ENUM ('discovery', 'update') NULL;

--rollback ALTER TABLE topic_projects MODIFY COLUMN run_mode ENUM ('first_build', 'nightly_update', 'discovery', 'update') NULL;
