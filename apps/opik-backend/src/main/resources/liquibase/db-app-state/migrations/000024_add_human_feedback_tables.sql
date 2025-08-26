--liquibase formatted sql
--changeset system:000024_add_human_feedback_tables
--comment: Create tables for Human Feedback system - annotation queues, queue items, and annotations

-- annotation_queues table
CREATE TABLE annotation_queues (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    status VARCHAR(20) DEFAULT 'active' CHECK (status IN ('active', 'completed', 'paused')),
    created_by VARCHAR(36) NOT NULL,
    project_id VARCHAR(36) NOT NULL,
    template_id VARCHAR(36),
    visible_fields JSON DEFAULT '["input", "output", "timestamp"]',
    required_metrics JSON DEFAULT '["rating"]',
    optional_metrics JSON DEFAULT '["comment"]',
    instructions TEXT,
    due_date TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE,
    INDEX idx_annotation_queues_project_id (project_id),
    INDEX idx_annotation_queues_created_by (created_by),
    INDEX idx_annotation_queues_status (status)
);

-- queue_items table
CREATE TABLE queue_items (
    id VARCHAR(36) PRIMARY KEY,
    queue_id VARCHAR(36) NOT NULL,
    item_type VARCHAR(10) NOT NULL CHECK (item_type IN ('trace', 'thread')),
    item_id VARCHAR(36) NOT NULL,
    status VARCHAR(20) DEFAULT 'pending' CHECK (status IN ('pending', 'in_progress', 'completed', 'skipped')),
    assigned_sme VARCHAR(36),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP,
    
    FOREIGN KEY (queue_id) REFERENCES annotation_queues(id) ON DELETE CASCADE,
    UNIQUE KEY unique_queue_item (queue_id, item_type, item_id),
    INDEX idx_queue_items_queue_id_status (queue_id, status),
    INDEX idx_queue_items_assigned_sme (assigned_sme),
    INDEX idx_queue_items_item_type_item_id (item_type, item_id)
);

-- annotations table
CREATE TABLE annotations (
    id VARCHAR(36) PRIMARY KEY,
    queue_item_id VARCHAR(36) NOT NULL,
    sme_id VARCHAR(36) NOT NULL,
    metrics JSON NOT NULL DEFAULT '{}',
    comment TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    
    FOREIGN KEY (queue_item_id) REFERENCES queue_items(id) ON DELETE CASCADE,
    UNIQUE KEY unique_annotation_per_sme (queue_item_id, sme_id),
    INDEX idx_annotations_queue_item_id (queue_item_id),
    INDEX idx_annotations_sme_id (sme_id)
);