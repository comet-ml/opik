--liquibase formatted sql
--changeset boryst:000136_add_ticket_key_to_cipx_session_analysis_tasks
--comment: Per-task ticket key (e.g. OPIK-1234) linking session analysis tasks to cipx_tickets (OPIK-8834)

-- One more parallel array in the `tasks` nested structure. The link lives on the task, not on
-- the ticket, so the analysis runner writes it in the same row as the task: concurrent analyses
-- cannot drop each other's links and a re-analysis re-links its own tasks. '' means the task has
-- no ticket, which is also what rows written before this column existed read per task.
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_session_analysis ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS `tasks.ticket_key` Array(String);

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_session_analysis ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS `tasks.ticket_key`;

