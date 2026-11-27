--liquibase formatted sql
--changeset yaroslavb:000128_add_topic_projects_labels_fingerprint
--comment: Topics labelling pass: the labeller the window's labels were last completed with, so a pass relabels only when it changes (OPIK-9410)

-- Null until a pass completes: the next pass of every project relabels its window once.
ALTER TABLE topic_projects
    ADD COLUMN labels_fingerprint CHAR(64) NULL AFTER labels_complete;

--rollback ALTER TABLE topic_projects DROP COLUMN labels_fingerprint;
