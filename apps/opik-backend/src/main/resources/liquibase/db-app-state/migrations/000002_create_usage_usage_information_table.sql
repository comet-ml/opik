--liquibase formatted sql
--changeset thiagohora:create_usage_usage_information_table

CREATE TABLE metadata (
    `key` VARCHAR(255) NOT NULL,
    value VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY `metadata_pk` (`key`)
);

CREATE TABLE usage_information (
    event_type VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    reported_at TIMESTAMP(6) DEFAULT NULL,
    PRIMARY KEY `usage_information_pk` (event_type)
);

--rollback DROP TABLE IF EXISTS metadata;
--rollback DROP TABLE IF EXISTS usage_information;
