--liquibase formatted sql
--changeset aswynz:000115_create_automation_watermarks
--comment: Create automation_watermarks: where a counting trigger (suite grew, queue submitted) last fired

-- test_suite:grew and annotation_queue:submitted fire when N things happened since the last firing, so
-- each (automation, trigger) keeps the instant it last fired and the count it saw then. Polled, not
-- evented: neither adding an item nor annotating one posts an event today.
CREATE TABLE IF NOT EXISTS automation_watermarks (
    automation_id CHAR(36) NOT NULL,
    trigger_key VARCHAR(255) NOT NULL,
    last_fired_at TIMESTAMP(6) NOT NULL,
    last_count INT NOT NULL DEFAULT 0,
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    CONSTRAINT automation_watermarks_pk PRIMARY KEY (automation_id, trigger_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

--rollback DROP TABLE IF EXISTS automation_watermarks;
