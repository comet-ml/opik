--liquibase formatted sql
--changeset yaroslavb:000120_create_topic_vector_watermarks
--comment: Create topic_vector_watermarks: where the vectors tick reads each workspace from (OPIK-8981)

-- watermark: where the vectors tick's next window read of the workspace starts, less topics.vectors.watermarkOverlap.
-- The tick moves it past the done statuses it gave a vector or will never give one, and never past one it left for a
-- later try. No row until the tick first reads the workspace.
CREATE TABLE IF NOT EXISTS topic_vector_watermarks
(
    workspace_id    VARCHAR(150) NOT NULL,
    watermark       TIMESTAMP(6) NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),

    PRIMARY KEY (workspace_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

--rollback DROP TABLE IF EXISTS topic_vector_watermarks;
