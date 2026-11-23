--liquibase formatted sql
--changeset yaroslavb:000116_rename_topic_run_error_codes
--comment: Name the LLM failures of a topics run without the model's name: qwen_* -> llm_* (OPIK-8987)

-- Nothing writes topic_projects yet, so no row holds an old value.
ALTER TABLE topic_projects
    MODIFY run_error_code ENUM ('llm_timeout', 'llm_error', 'llm_bad_answer', 'window_read', 'invariant', 'timeout',
                                'save', 'internal', 'abandoned') NULL;

--rollback ALTER TABLE topic_projects MODIFY run_error_code ENUM ('qwen_timeout', 'qwen_error', 'qwen_bad_answer', 'window_read', 'invariant', 'timeout', 'save', 'internal', 'abandoned') NULL;
