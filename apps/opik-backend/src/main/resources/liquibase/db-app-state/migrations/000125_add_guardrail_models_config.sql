--liquibase formatted sql
--changeset aliaksandrk:000125_add_guardrail_models_config
--comment: Add guardrail_models.config, what a model's classifier is asked, and make the description optional (OPIK-9373)

-- config holds what the classifier is asked, {"prompt": "..."}, as the online evaluators' code column holds theirs:
-- later settings, such as what "yes" and "no" mean, become keys with no migration. 000118 rendered the question from
-- the description; it is the prompt now, so existing models keep their description as their prompt. The description
-- becomes an optional note for people, which no check reads.
--
-- The rollback drops config only: description stays nullable, since a model created after this migration may have no
-- description to restore.
ALTER TABLE guardrail_models ADD COLUMN config JSON NULL AFTER description;
UPDATE guardrail_models SET config = JSON_OBJECT('prompt', description);
ALTER TABLE guardrail_models
    MODIFY COLUMN config JSON NOT NULL,
    MODIFY COLUMN description VARCHAR(1000) NULL;

--rollback ALTER TABLE guardrail_models DROP COLUMN config;
