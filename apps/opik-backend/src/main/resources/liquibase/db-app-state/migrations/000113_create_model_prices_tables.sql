--liquibase formatted sql
--changeset aadereiko:000113_create_model_prices_tables
--comment: Create model_price_lists, model_prices and model_prices_history — an organization's custom model prices (OPIK-8626)

-- One header per organization: the list version that each saved change raises, which spans record with their
-- cost, and the number of rows in the list. Writers lock this row first, so two saves of one organization's list
-- run one after the other.
CREATE TABLE IF NOT EXISTS model_price_lists
(
    organization_id VARCHAR(150) NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    row_count       INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by      VARCHAR(100) NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100) NOT NULL DEFAULT 'admin',

    CONSTRAINT model_price_lists_pk PRIMARY KEY (organization_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- One row per custom price. No foreign key to model_price_lists, like every state table since 000042: the
-- service writes both in one transaction.
CREATE TABLE IF NOT EXISTS model_prices
(
    id                CHAR(36)                NOT NULL,
    organization_id   VARCHAR(150)            NOT NULL,
    -- Stored normalized, the form span names are looked up in (lower case, provider prefix removed).
    model_name        VARCHAR(255)            NOT NULL,
    -- '' means any provider. NOT NULL so the unique key below holds: MySQL treats every NULL as distinct, which
    -- would let two any-provider rows for one model both save.
    provider          VARCHAR(255)            NOT NULL DEFAULT '',
    kind              ENUM ('fixed', 'alias') NOT NULL,
    -- USD per 1M tokens, exactly as typed. NULL inherits the built-in rate and 0 makes it free. Ten decimals keep
    -- a mistyped per-token value instead of rounding it to 0, so the out-of-range warning can catch it.
    input_price       DECIMAL(20, 10)         NULL,
    output_price      DECIMAL(20, 10)         NULL,
    cache_read_price  DECIMAL(20, 10)         NULL,
    cache_write_price DECIMAL(20, 10)         NULL,
    alias_model_name  VARCHAR(255)            NULL,
    -- '' means the span's own provider.
    alias_provider    VARCHAR(255)            NULL,
    note              VARCHAR(500)            NULL,
    created_at        TIMESTAMP(6)            NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by        VARCHAR(100)            NOT NULL DEFAULT 'admin',
    last_updated_at   TIMESTAMP(6)            NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by   VARCHAR(100)            NOT NULL DEFAULT 'admin',

    CONSTRAINT model_prices_pk PRIMARY KEY (id),
    -- Also serves the per-organization read, through its organization_id prefix.
    CONSTRAINT model_prices_org_model_provider_uk UNIQUE (organization_id, model_name, provider)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- Append-only: one row per changed price, with the list version the change produced and the row before and after.
-- row_kind already lists 'discount' so the separate discount-rules project needs no MODIFY COLUMN here; only
-- 'price' is written until then.
CREATE TABLE IF NOT EXISTS model_prices_history
(
    id              CHAR(36)                             NOT NULL,
    organization_id VARCHAR(150)                         NOT NULL,
    list_version    BIGINT                               NOT NULL,
    row_kind        ENUM ('price', 'discount')           NOT NULL DEFAULT 'price',
    row_id          CHAR(36)                             NOT NULL,
    action          ENUM ('created', 'updated', 'deleted') NOT NULL,
    before_row      JSON                                 NULL,
    after_row       JSON                                 NULL,
    created_at      TIMESTAMP(6)                         NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by      VARCHAR(100)                         NOT NULL DEFAULT 'admin',

    CONSTRAINT model_prices_history_pk PRIMARY KEY (id),
    INDEX model_prices_history_org_version_idx (organization_id, list_version),
    INDEX model_prices_history_org_row_idx (organization_id, row_kind, row_id, list_version)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

--rollback DROP TABLE IF EXISTS model_prices_history;
--rollback DROP TABLE IF EXISTS model_prices;
--rollback DROP TABLE IF EXISTS model_price_lists;
