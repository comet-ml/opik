# Opik CLI Export/Import Commands

This document describes the `opik export` and `opik import` CLI commands that allow you to export trace data from projects and import it to other projects.

## Overview

The export/import functionality enables you to:
- **Export**: Export all traces, spans, datasets, prompts, and evaluation rules from a project to local JSON files
- **Import**: Import data from local files into a project
- **Migrate**: Move data between projects or environments
- **Backup**: Create local backups of your project data

## Commands

### `opik export WORKSPACE_OR_PROJECT`

Exports all trace data from the specified workspace or project to local files.

**Arguments:**
- `WORKSPACE_OR_PROJECT`: Either a workspace name (e.g., "my-workspace") to export all projects, or workspace/project (e.g., "my-workspace/my-project") to export a specific project

**Options:**
- `--path, -p`: Directory to save exported data (default: `./`)
- `--max-results`: Maximum number of items to export per data type (default: 1000)
- `--filter`: Filter string using Opik Query Language (OQL) to narrow down the search
- `--include`: Data types to include (traces, datasets, prompts)
- `--exclude`: Data types to exclude
- `--all`: Include all data types
- `--name`: Filter items by name using Python regex patterns

**Examples:**
```bash
# Export all traces from a project
opik export my-workspace/my-project

# Export all data types from a workspace
opik export my-workspace --all

# Export only datasets
opik export my-workspace/my-project --include datasets

# Export with custom output directory
opik export my-workspace/my-project --path ./backup_data

# Export with filter and limit
opik export my-workspace/my-project --filter "start_time >= '2024-01-01T00:00:00Z'" --max-results 100
```

### `opik import WORKSPACE_FOLDER WORKSPACE_NAME`

Imports trace data from local files to the specified workspace or project.

**Arguments:**
- `WORKSPACE_FOLDER`: Directory containing JSON files to import
- `WORKSPACE_NAME`: The name of the workspace or workspace/project to import traces to

**Options:**
- `--dry-run`: Show what would be imported without actually importing
- `--include`: Data types to include (traces, datasets, prompts)
- `--exclude`: Data types to exclude
- `--all`: Include all data types
- `--name`: Filter items by name using Python regex patterns

**Examples:**
```bash
# Import traces to a project
opik import ./my-data my-workspace/my-target-project

# Import all data types
opik import ./my-data my-workspace/my-target-project --all

# Import only datasets
opik import ./my-data my-workspace/my-target-project --include datasets

# Import with custom input directory
opik import ./backup_data my-workspace/my-target-project

# Dry run to see what would be imported
opik import ./my-data my-workspace/my-target-project --dry-run
```

## File Format

The exported data is stored in JSON files with the following structure:

```
OUTPUT_DIR/
└── WORKSPACE/
    └── PROJECT_NAME/
        ├── trace_TRACE_ID_1.json
        ├── trace_TRACE_ID_2.json
        ├── dataset_DATASET_NAME.json
        └── prompt_PROMPT_NAME.json
```

Each trace file contains:
```json
{
  "trace": {
    "id": "trace-uuid",
    "name": "trace-name",
    "start_time": "2024-01-01T00:00:00Z",
    "end_time": "2024-01-01T00:01:00Z",
    "input": {...},
    "output": {...},
    "metadata": {...},
    "tags": [...],
    "thread_id": "thread-uuid"
  },
  "spans": [
    {
      "id": "span-uuid",
      "name": "span-name",
      "start_time": "2024-01-01T00:00:00Z",
      "end_time": "2024-01-01T00:01:00Z",
      "input": {...},
      "output": {...},
      "metadata": {...},
      "type": "general",
      "model": "gpt-4",
      "provider": "openai"
    }
  ],
  "downloaded_at": "2024-01-01T00:00:00Z",
  "project_name": "source-project"
}
```

Each evaluation rule file contains:
```json
{
  "id": "rule-uuid",
  "name": "rule-name",
  "project_id": "project-uuid",
  "project_name": "project-name",
  "sampling_rate": 1.0,
  "enabled": true,
  "filters": [...],
  "action": "evaluator",
  "type": "llm_as_judge",
  "created_at": "2024-01-01T00:00:00Z",
  "created_by": "user-id",
  "last_updated_at": "2024-01-01T00:00:00Z",
  "last_updated_by": "user-id",
  "evaluator_data": {
    "llm_as_judge_code": {
      "prompt": "Evaluate the response...",
      "model": "gpt-4",
      "temperature": 0.0
    }
  },
  "downloaded_at": "2024-01-01T00:00:00Z"
}
```

## Use Cases

### 1. Project Migration
```bash
# Export all data from source project
opik export my-workspace/old-project --all --path ./migration_data

# Import to new project (specify the workspace/project directory)
opik import ./migration_data/my-workspace/old-project my-workspace/new-project --all
```

### 2. Data Backup
```bash
# Create backup of all data
opik export my-workspace/production-project --all --path ./backup_$(date +%Y%m%d)
```

### 3. Environment Sync
```bash
# Sync from staging to production
opik export my-workspace/staging-project --filter "tags contains 'ready-for-prod'"
opik import ./exported_data my-workspace/production-project
```

### 4. Data Analysis
```bash
# Export specific traces for analysis
opik export my-workspace/my-project --filter "start_time >= '2024-01-01T00:00:00Z'" --max-results 1000
# Analyze the JSON files locally
```

### 5. Dataset Management
```bash
# Export datasets from a project
opik export my-workspace/my-project --include datasets

# Import datasets to another project
opik import ./exported_data my-workspace/target-project --include datasets
```

## Error Handling

The commands include comprehensive error handling:
- **Network errors**: Automatic retry with user feedback
- **Authentication errors**: Clear error messages with setup instructions
- **File system errors**: Proper directory creation and permission handling
- **Data validation**: JSON format validation and error reporting

## Progress Tracking

Both commands show progress indicators:
- **Export**: Shows number of traces found and export progress
- **Import**: Shows number of files found and import progress
- **Rich output**: Color-coded status messages and progress bars

## Limitations

- **Large datasets**: For projects with many traces, consider using filters to limit exports
- **Network dependency**: Requires active connection to Opik server
- **Authentication**: Must be properly configured with API keys
- **File size**: Large trace files may take time to process

## Troubleshooting

### Common Issues

1. **"No traces found"**
   - Check if the project name is correct
   - Verify you have access to the project
   - Try without filters first

2. **"Project directory not found"**
   - Make sure you've exported data first
   - Check the input directory path
   - Verify the project name matches

3. **"Opik SDK not available"**
   - Ensure Opik is properly installed
   - Check your Python environment
   - Verify the installation with `opik healthcheck`


### Getting Help

```bash
# Get help for export command
opik export --help

# Get help for import command
opik import --help

# Check system health
opik healthcheck
```

## Example Workflow

Here's a complete example of exporting and importing trace data:

```bash
# 1. Export traces from source project
opik export my-workspace/my-source-project --path ./temp_data

# 2. Inspect the exported data
ls ./temp_data/my-workspace/my-source-project/
cat ./temp_data/my-workspace/my-source-project/trace_*.json | head -20

# 3. Dry run import to see what would be imported
opik import ./temp_data my-workspace/my-target-project --dry-run

# 4. Actually import the traces
opik import ./temp_data my-workspace/my-target-project

# 5. Clean up temporary data
rm -rf ./temp_data
```

This workflow ensures you can safely migrate trace data between projects while maintaining data integrity and providing visibility into the process.
