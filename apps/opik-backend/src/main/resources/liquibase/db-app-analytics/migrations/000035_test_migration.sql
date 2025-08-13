--liquibase formatted sql
--changeset idoberk:000035_test_migration

SELECT 1;

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.workspace_configurations ON CLUSTER '{cluster}'; 
