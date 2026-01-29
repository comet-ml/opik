# Database Migration Patterns

## Migration Locations
- **MySQL**: `apps/opik-backend/src/main/resources/liquibase/db-app-state/migrations/`
- **ClickHouse**: `apps/opik-backend/src/main/resources/liquibase/db-app-analytics/migrations/`

## Liquibase Format

```sql
--liquibase formatted sql
--changeset author:000001_description
--comment: Brief description of the migration

-- Your SQL here

```
**Always end with empty line.**

## MySQL Migration Example

```sql
--liquibase formatted sql
--changeset john.doe:000001_add_user_table
--comment: Create users table with authentication fields

CREATE TABLE users (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- Index for email lookups (frequent login queries)
CREATE INDEX idx_users_email ON users(email);

```

## ClickHouse Migration Example

```sql
--liquibase formatted sql
--changeset john.doe:000001_add_analytics_table
--comment: Create analytics events table

CREATE TABLE IF NOT EXISTS analytics_events ON CLUSTER '{cluster}' (
    id              FixedString(36),
    workspace_id    String,
    event_type      Enum8('unknown' = 0, 'view' = 1, 'click' = 2),
    created_at      DateTime64(9, 'UTC') DEFAULT now64(9),
    last_updated_at DateTime64(6, 'UTC') DEFAULT now64(6)
)
ENGINE = ReplicatedReplacingMergeTree(
    '/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/analytics_events',
    '{replica}',
    last_updated_at
)
ORDER BY (workspace_id, event_type, id);

```

## ClickHouse Gotchas

- **Always use** `ON CLUSTER '{cluster}'` for distributed operations
- **Engine**: Use `ReplicatedReplacingMergeTree` for deduplication, `ReplicatedMergeTree` for audit/logs
- **ORDER BY**: Include workspace_id first, then logical groupings

## Index Comments

Always explain why an index exists:

```sql
-- ❌ BAD - No explanation
CREATE INDEX idx_users_created_at ON users(created_at);

-- ✅ GOOD - Explains purpose
-- Index for user registration analytics (used in monthly reports)
CREATE INDEX idx_users_created_at ON users(created_at);
```
