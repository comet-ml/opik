--liquibase formatted sql
--changeset thiagohora:init_script

ALTER DATABASE `${STATE_DB_DATABASE_NAME}` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

SET @@group_concat_max_len = 2048;

CREATE TABLE projects (
    id CHAR(36) NOT NULL,
    name VARCHAR(150) NOT NULL,
    workspace_id VARCHAR(150) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    CONSTRAINT `projects_pk` PRIMARY KEY (id),
    CONSTRAINT `projects_workspace_id_name_uk` UNIQUE (workspace_id, name)
);

INSERT INTO `projects` (`id`, `name`, `description`, `workspace_id`) VALUES ('0190babc-62a0-71d2-832a-0feffa4676eb', 'Default Project', 'This is the default project. It cannot be deleted.', '0190babc-62a0-71d2-832a-0feffa4676eb');

CREATE TABLE feedback_definitions (
      id CHAR(36) NOT NULL,
      name VARCHAR(150) NOT NULL,
      type ENUM('numerical', 'categorical') NOT NULL,
      details JSON NOT NULL,
      created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
      created_by VARCHAR(100) NOT NULL DEFAULT 'admin',
      last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
      last_updated_by VARCHAR(100) NOT NULL DEFAULT 'admin',
      workspace_id VARCHAR(150) NOT NULL,
      CONSTRAINT `feedbacks_pk` PRIMARY KEY (id),
      CONSTRAINT `feedbacks_workspace_id_name_uk` UNIQUE (workspace_id, name),
      INDEX `feedbacks_workspace_id_type` (workspace_id, type)
);

CREATE TABLE datasets (
    id CHAR(36) NOT NULL,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    workspace_id VARCHAR(150) NOT NULL,
    CONSTRAINT `datasets_pk` PRIMARY KEY (id),
    CONSTRAINT `datasets_workspace_id_name_uk` UNIQUE (workspace_id, name)
);

--rollback DROP TABLE IF EXISTS datasets;
--rollback DROP TABLE IF EXISTS feedback_definitions;
--rollback DELETE FROM `projects` WHERE `id` = '0190babc-62a0-71d2-832a-0feffa4676eb';
--rollback DROP TABLE IF EXISTS project;
--rollback SET @@group_concat_max_len = 1024;
--rollback ALTER DATABASE `${STATE_DB_DATABASE_NAME}` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai;
