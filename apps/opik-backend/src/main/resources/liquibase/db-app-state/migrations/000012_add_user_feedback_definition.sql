--liquibase formatted sql
--changeset thiagohora:add_user_feedback_definition

INSERT INTO `feedback_definitions` (`id`, `name`, `type`, `details`, `workspace_id`) VALUES ('0190babc-62a0-71d2-832a-0feffa4676eb', 'User feedback', 'categorical', '{ "categories": { "👍": 1.0, "👎": 0.0 } }', '0190babc-62a0-71d2-832a-0feffa4676eb');

--rollback DELETE FROM `feedback_definitions` WHERE `id` = '0190babc-62a0-71d2-832a-0feffa4676eb';
