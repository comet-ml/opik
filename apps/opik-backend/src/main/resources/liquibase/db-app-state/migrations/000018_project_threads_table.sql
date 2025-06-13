--liquibase formatted sql
--changeset thiagohora:000018_project_trace_threads_table

CREATE TABLE IF NOT EXISTS project_trace_threads (
    id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    thread_id VARCHAR(150) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    CONSTRAINT `project_trace_threads_pk` PRIMARY KEY (id),
    CONSTRAINT `project_trace_threads_uk` UNIQUE (project_id, thread_id)
);

--rollback DROP TABLE IF EXISTS project_trace_threads;
