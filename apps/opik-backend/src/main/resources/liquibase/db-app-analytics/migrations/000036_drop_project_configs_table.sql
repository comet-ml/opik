--liquibase formatted sql
--changeset thiagohora:000036_drop_project_configs_table

DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.project_configurations ON CLUSTER '{cluster}';

--rollback empty
