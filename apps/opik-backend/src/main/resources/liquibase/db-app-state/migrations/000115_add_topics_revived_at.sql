--liquibase formatted sql
--changeset yaroslavb:000115_add_topics_revived_at
--comment: Add topics.revived_at, the night a retired topic came back (OPIK-8988)

-- A topic retired by low_share, cap or replaced stays dormant for topics.nightly.reviveDays: its prototypes stay in
-- topic_prototypes, and when its traffic comes back the nightly update makes it live again instead of creating a new
-- topic. revived_at is the last night it came back.
ALTER TABLE topics ADD COLUMN revived_at DATE NULL AFTER retired_reason;

--rollback ALTER TABLE topics DROP COLUMN revived_at;
