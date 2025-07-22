CREATE TABLE dashboards (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    layout JSONB NOT NULL,
    filters JSONB NOT NULL DEFAULT '{}',
    refresh_interval INTEGER NOT NULL DEFAULT 30,
    workspace_id VARCHAR(255) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(255) NOT NULL,
    last_updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    last_updated_by VARCHAR(255) NOT NULL
);

-- Add indices for performance
CREATE INDEX idx_dashboards_workspace_id ON dashboards(workspace_id);
CREATE INDEX idx_dashboards_created_at ON dashboards(created_at);
CREATE INDEX idx_dashboards_name ON dashboards(name);

-- Add foreign key constraint if workspaces table exists
-- ALTER TABLE dashboards ADD CONSTRAINT fk_dashboards_workspace 
--     FOREIGN KEY (workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE;
