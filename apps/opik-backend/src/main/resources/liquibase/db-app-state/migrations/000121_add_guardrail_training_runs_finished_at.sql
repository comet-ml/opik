--liquibase formatted sql
--changeset aliaksandrk:000121_add_guardrail_training_runs_finished_at
--comment: Add finished_at and a (model_id, created_at) index to guardrail_training_runs (OPIK-9325)

-- finished_at: when the run reached COMPLETED, FAILED or CANCELLED, set once by the status write that ends it; null
-- while the run is in flight. 000118 took a finished run's last_updated_at as its finish time, but any later write to
-- the row moves that column (a metadata update, a status_message, last_updated_by), so the finish time the Models tab
-- shows, and the grace period and retention will read, would drift.
--
-- The new index serves the reads over all of a model's runs, newest first: its latest run and its run history. On
-- (model_id, status, created_at), status sits between model_id and created_at, so those reads sorted the model's runs;
-- this index returns them in order. Runs created in the same microsecond are ordered by id (UUID v7), which InnoDB
-- appends to every secondary index as the primary key, so the index needs no id column of its own. While a model has a
-- few runs, MySQL still prefers the status index and sorts them, which costs nothing; as a model's runs grow it
-- switches to this one. The status index keeps serving the reads by status: the serving run, the run in flight, the
-- previous attempt.
--
-- Nothing on main writes guardrail_training_runs yet, so there is no row to backfill.
ALTER TABLE guardrail_training_runs
    ADD COLUMN finished_at TIMESTAMP(6) NULL AFTER metadata,
    ADD INDEX guardrail_training_runs_model_created_idx (model_id, created_at);

--rollback ALTER TABLE guardrail_training_runs DROP INDEX guardrail_training_runs_model_created_idx, DROP COLUMN finished_at;
