--liquibase formatted sql
--changeset boryst:000107_add_git_delta_to_cipx_trace_identities
--comment: Add per-turn git info + committed delta to cipx_trace_identities (branch/head_sha/dirty + commit/file/line counts, OPIK-7345)

-- The proxy already emits the session repo's branch / head_sha / dirty (the hook's snapshot at prompt
-- time) but the BE only persisted the remote, so add them (Group A). The proxy now also computes a
-- per-turn committed git delta (head_sha_start..HEAD): how many commits the turn landed and the
-- added/deleted file and line counts of that committed work, for the AI-spend "content delivered"
-- analysis (Group B). A cipx trace is one Claude Code turn, so these are trace-level and land here.
-- Additive columns with defaults; existing rows read the defaults. Notes:
--   head_sha_start:   the turn-start HEAD (= the proxy's repository.head_sha), head_sha_end HEAD now.
--   files_added/deleted: created / removed files only; in-place edits show only in the line counts.
--   commit/file/line counts cover committed work only (uncommitted working-tree changes are excluded).
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}'
    ADD COLUMN IF NOT EXISTS branch           String DEFAULT '',
    ADD COLUMN IF NOT EXISTS head_sha_start   String DEFAULT '',
    ADD COLUMN IF NOT EXISTS head_sha_end     String DEFAULT '',
    ADD COLUMN IF NOT EXISTS dirty            Bool   DEFAULT false,
    ADD COLUMN IF NOT EXISTS commits_in_trace UInt32 DEFAULT 0,
    ADD COLUMN IF NOT EXISTS files_added      UInt32 DEFAULT 0,
    ADD COLUMN IF NOT EXISTS files_deleted    UInt32 DEFAULT 0,
    ADD COLUMN IF NOT EXISTS lines_added      UInt32 DEFAULT 0,
    ADD COLUMN IF NOT EXISTS lines_deleted    UInt32 DEFAULT 0;

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.cipx_trace_identities ON CLUSTER '{cluster}' DROP COLUMN IF EXISTS branch, DROP COLUMN IF EXISTS head_sha_start, DROP COLUMN IF EXISTS head_sha_end, DROP COLUMN IF EXISTS dirty, DROP COLUMN IF EXISTS commits_in_trace, DROP COLUMN IF EXISTS files_added, DROP COLUMN IF EXISTS files_deleted, DROP COLUMN IF EXISTS lines_added, DROP COLUMN IF EXISTS lines_deleted;

