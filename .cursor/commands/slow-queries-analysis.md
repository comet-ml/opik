# Slow Queries Analysis

Analyze slow queries in ClickHouse by querying the system query log.

## Configuration

**Environment**: Ask user which environment to analyze (local or prod)

### Local Development (default)
```
CLICKHOUSE_ENV=local
CLICKHOUSE_HOST=localhost
CLICKHOUSE_PORT=9000
CLICKHOUSE_USER=opik
CLICKHOUSE_PASSWORD=opik
CLICKHOUSE_DATABASE=opik
```

### Production
```
CLICKHOUSE_ENV=prod
CLICKHOUSE_HOST=<prod-clickhouse-host>
CLICKHOUSE_PORT=8123
CLICKHOUSE_USER=<prod-user>
CLICKHOUSE_PASSWORD=<prod-password>
CLICKHOUSE_DATABASE=opik
CLICKHOUSE_SECURE=--secure
```

**Note**: For production, you'll need to provide credentials. Never commit production credentials to the repository.

## Instructions

1. Ask the user which environment to analyze: **local** or **prod**
2. If prod, ask for connection details (host, user, password) or use environment variables
3. Connect to the ClickHouse database
4. Run the slow queries analysis queries against `system.query_log`
5. **Run the detailed granules breakdown query** (query #4) - this is CRITICAL for identifying index issues
6. **Get full query text** for top slow queries (query #5) - needed for EXPLAIN analysis
7. **Run EXPLAIN indexes=1** on the top 3-5 slowest queries (query #6) - shows parts x/y and granules x/y
8. Present the results in a readable format with recommendations
9. **Include the Granules Breakdown section** in the report with:
   - Granules in x/y format (selected/total with percentage)
   - Parts analysis showing selected/total ratio
   - Skip ratio assessment
   - EXPLAIN output showing parts and granules filtering
10. **Map slow queries to source code**: For each slow query identified, search the Opik codebase to find the DAO/repository file where the query is defined
11. **Export results to markdown**: Save the analysis results to `/tmp/slow-queries-analysis-{timestamp}.md`

## Source Code Mapping

After identifying slow queries, search for their source locations in the codebase using these patterns:

### Known Query Patterns → Source Files

| Query Pattern | Source File |
|---------------|-------------|
| `queues_final` | `apps/opik-backend/src/main/java/com/comet/opik/domain/AnnotationQueueDAO.java` |
| `experiments_final` | `apps/opik-backend/src/main/java/com/comet/opik/domain/ExperimentDAO.java` |
| `optimization_final` | `apps/opik-backend/src/main/java/com/comet/opik/domain/OptimizationDAO.java` |
| `trace_threads.*final` | `apps/opik-backend/src/main/java/com/comet/opik/domain/threads/TraceThreadDAO.java` |
| `spans_agg` | `apps/opik-backend/src/main/java/com/comet/opik/domain/SpanDAO.java` |
| `traces FINAL` | `apps/opik-backend/src/main/java/com/comet/opik/domain/TraceDAO.java` |
| `feedback_scores` | `apps/opik-backend/src/main/java/com/comet/opik/domain/FeedbackScoreDAO.java` |
| `experiment_items` | `apps/opik-backend/src/main/java/com/comet/opik/domain/ExperimentItemDAO.java` |

### How to Find Query Source

To find the source of an unrecognized slow query, search the backend for distinctive patterns:

```bash
# Search for CTE names (WITH ... AS patterns)
grep -r "WITH.*_final AS" apps/opik-backend/src/main/java/

# Search for specific table names
grep -r "FROM traces" apps/opik-backend/src/main/java/

# Search for specific column patterns  
grep -r "workspace_id.*project_id" apps/opik-backend/src/main/java/
```

## ClickHouse Connection

### Local Development (from `deployment/docker-compose/docker-compose.yaml`):
- Container: `opik-clickhouse-1`
- Host: `localhost`
- Port: `8123` (HTTP) or `9000` (native)
- Database: `opik`
- User: `opik`
- Password: `opik`

### Production:
- Host: Provided by user or from environment variable `CLICKHOUSE_PROD_HOST`
- Port: `9440` (secure) or `8443` (HTTPS)
- User: Provided by user or from environment variable `CLICKHOUSE_PROD_USER`
- Password: Provided by user or from environment variable `CLICKHOUSE_PROD_PASSWORD`
- Database: `opik`
- Secure: `--secure` flag required

## Slow Queries Analysis Query

Run this query to find the slowest queries from the last 600 MINUTEs:

```sql
SELECT
    query_id,
    user,
    query_start_time,
    query_duration_ms,
    read_rows,
    read_bytes,
    result_rows,
    result_bytes,
    memory_usage,
    formatReadableSize(read_bytes) AS read_bytes_readable,
    formatReadableSize(memory_usage) AS memory_usage_readable,
    extract(query, 'workspace_id\\s*=\\s*''([0-9a-f-]{36})''') AS workspace_id,
    extract(query, 'project_id\\s*=\\s*''([0-9a-f-]{36})''') AS project_id,
    substring(query, 1, 200) AS query_preview,
    type,
    exception
FROM system.query_log
WHERE 
    event_date = today()
    AND event_time >= now() - INTERVAL 600 MINUTE
    AND type IN ('QueryFinish', 'ExceptionWhileProcessing')
    AND query_duration_ms > 1000
    AND query NOT LIKE '%system.query_log%'
ORDER BY query_duration_ms DESC
LIMIT 50
```

## Additional Analysis Queries

### Top queries by memory usage:

```sql
SELECT
    query_id,
    query_start_time,
    query_duration_ms,
    formatReadableSize(memory_usage) AS memory_usage,
    read_rows,
    substring(query, 1, 200) AS query_preview
FROM system.query_log
WHERE 
    event_date = today()
    AND event_time >= now() - INTERVAL 600 MINUTE
    AND type = 'QueryFinish'
    AND query NOT LIKE '%system.query_log%'
ORDER BY memory_usage DESC
LIMIT 20
```

### Query patterns by average execution time:

```sql
SELECT
    normalized_query_hash,
    count() AS query_count,
    avg(query_duration_ms) AS avg_duration_ms,
    max(query_duration_ms) AS max_duration_ms,
    sum(read_rows) AS total_read_rows,
    any(substring(query, 1, 150)) AS query_sample
FROM system.query_log
WHERE 
    event_date = today()
    AND event_time >= now() - INTERVAL 600 MINUTE
    AND type = 'QueryFinish'
    AND query NOT LIKE '%system.query_log%'
GROUP BY normalized_query_hash
HAVING avg_duration_ms > 500
ORDER BY avg_duration_ms DESC
LIMIT 30
```

### Failed queries:

```sql
SELECT
    query_id,
    query_start_time,
    exception_code,
    exception,
    substring(query, 1, 200) AS query_preview
FROM system.query_log
WHERE 
    event_date = today()
    AND event_time >= now() - INTERVAL 600 MINUTE
    AND type = 'ExceptionWhileProcessing'
    AND query NOT LIKE '%system.query_log%'
ORDER BY query_start_time DESC
LIMIT 20
```

## Granules & Index Analysis (EXPLAIN)

Use `EXPLAIN indexes=1` to analyze how ClickHouse uses indexes and granules for a specific query.
This helps identify inefficient queries that scan too many granules.

### Basic EXPLAIN with indexes:

```sql
EXPLAIN indexes=1
SELECT * FROM traces
WHERE project_id = 'your-project-id'
  AND created_at >= now() - INTERVAL 1 DAY
LIMIT 100;
```

### Full EXPLAIN with all details:

```sql
EXPLAIN indexes=1, actions=1, header=1, description=1
SELECT * FROM spans
WHERE project_id = 'your-project-id'
  AND trace_id = 'your-trace-id';
```

### Analyze granules read vs skipped from query log:

```sql
SELECT
    query_id,
    query_start_time,
    query_duration_ms,
    read_rows,
    read_bytes,
    ProfileEvents['SelectedMarks'] AS selected_marks,
    ProfileEvents['SelectedRanges'] AS selected_ranges,
    ProfileEvents['SelectedParts'] AS selected_parts,
    ProfileEvents['MergeTreeDataSelectorsSelectedMarks'] AS marks_selected_by_index,
    ProfileEvents['MergeTreeDataSelectorsSkippedMarks'] AS marks_skipped_by_index,
    substring(query, 1, 200) AS query_preview
FROM system.query_log
WHERE 
    event_date = today()
    AND event_time >= now() - INTERVAL 600 MINUTE
    AND type = 'QueryFinish'
    AND query_duration_ms > 500
    AND query NOT LIKE '%system.query_log%'
ORDER BY query_duration_ms DESC
LIMIT 30
```

### Index efficiency ratio:

```sql
SELECT
    query_id,
    query_duration_ms,
    ProfileEvents['SelectedMarks'] AS marks_read,
    ProfileEvents['MergeTreeDataSelectorsSkippedMarks'] AS marks_skipped,
    if(ProfileEvents['SelectedMarks'] + ProfileEvents['MergeTreeDataSelectorsSkippedMarks'] > 0,
       round(ProfileEvents['MergeTreeDataSelectorsSkippedMarks'] * 100.0 / 
             (ProfileEvents['SelectedMarks'] + ProfileEvents['MergeTreeDataSelectorsSkippedMarks']), 2),
       0) AS skip_ratio_percent,
    substring(query, 1, 150) AS query_preview
FROM system.query_log
WHERE 
    event_date = today()
    AND event_time >= now() - INTERVAL 600 MINUTE
    AND type = 'QueryFinish'
    AND query_duration_ms > 100
    AND query NOT LIKE '%system.query_log%'
ORDER BY skip_ratio_percent ASC, query_duration_ms DESC
LIMIT 30
```

### EXPLAIN Output Interpretation

- **ReadFromMergeTree**: Shows which parts/granules will be read
- **Granules**: Total number of granules in the table
- **Selected**: Number of granules that match the query conditions  
- **Marks**: Index marks used for reading (1 mark = 8192 rows by default)
- **Parts**: Number of data parts being read

**Good index usage**: Low `Selected` vs high total `Granules` = index is effectively filtering
**Poor index usage**: `Selected` ≈ `Granules` = full table scan, consider adding/modifying indexes

## Commands to Execute

### For Local Development

Run via Docker:

#### 1. Slow Queries Analysis (main query) - LOCAL

```bash
docker exec opik-clickhouse-1 clickhouse-client --user opik --password opik --query "
SELECT
    query_id,
    user,
    query_start_time,
    query_duration_ms,
    read_rows,
    formatReadableSize(read_bytes) AS read_bytes,
    formatReadableSize(memory_usage) AS memory_usage,
    extract(query, 'workspace_id\\\\s*=\\\\s*''([0-9a-f-]{36})''') AS workspace_id,
    extract(query, 'project_id\\\\s*=\\\\s*''([0-9a-f-]{36})''') AS project_id,
    substring(query, 1, 200) AS query_preview
FROM system.query_log
WHERE 
    event_date = today()
    AND event_time >= now() - INTERVAL 600 MINUTE
    AND type IN ('QueryFinish', 'ExceptionWhileProcessing')
    AND query_duration_ms > 100
    AND query NOT LIKE '%system.query_log%'
ORDER BY query_duration_ms DESC
LIMIT 30
FORMAT PrettyCompact
"
```

#### 2. Granules Analysis - LOCAL

```bash
docker exec opik-clickhouse-1 clickhouse-client --user opik --password opik --query "
SELECT
    query_id,
    query_start_time,
    query_duration_ms,
    read_rows,
    ProfileEvents['SelectedMarks'] AS selected_marks,
    ProfileEvents['SelectedParts'] AS selected_parts,
    substring(query, 1, 150) AS query_preview
FROM system.query_log
WHERE 
    event_date = today()
    AND event_time >= now() - INTERVAL 600 MINUTE
    AND type = 'QueryFinish'
    AND query_duration_ms > 100
    AND query NOT LIKE '%system.query_log%'
ORDER BY query_duration_ms DESC
LIMIT 20
FORMAT PrettyCompact
"
```

#### 3. Index Efficiency Ratio - LOCAL

```bash
docker exec opik-clickhouse-1 clickhouse-client --user opik --password opik --query "
SELECT
    query_id,
    query_duration_ms,
    ProfileEvents['SelectedMarks'] AS marks_read,
    ProfileEvents['MergeTreeDataSelectorsSkippedMarks'] AS marks_skipped,
    if(ProfileEvents['SelectedMarks'] + ProfileEvents['MergeTreeDataSelectorsSkippedMarks'] > 0,
       round(ProfileEvents['MergeTreeDataSelectorsSkippedMarks'] * 100.0 / 
             (ProfileEvents['SelectedMarks'] + ProfileEvents['MergeTreeDataSelectorsSkippedMarks']), 2),
       0) AS skip_ratio_percent,
    substring(query, 1, 100) AS query_preview
FROM system.query_log
WHERE 
    event_date = today()
    AND event_time >= now() - INTERVAL 600 MINUTE
    AND type = 'QueryFinish'
    AND query_duration_ms > 50
    AND query NOT LIKE '%system.query_log%'
ORDER BY skip_ratio_percent ASC, query_duration_ms DESC
LIMIT 20
FORMAT PrettyCompact
"
```

#### 4. Detailed Granules Breakdown - LOCAL (ALWAYS RUN THIS)

```bash
docker exec opik-clickhouse-1 clickhouse-client --user opik --password opik --query "
SELECT
    query_id,
    query_start_time,
    query_duration_ms,
    read_rows,
    formatReadableSize(read_bytes) AS read_bytes,
    ProfileEvents['SelectedMarks'] AS selected_marks,
    ProfileEvents['SelectedParts'] AS selected_parts,
    ProfileEvents['SelectedRanges'] AS selected_ranges,
    ProfileEvents['MergeTreeDataSelectorsSelectedMarks'] AS marks_by_index,
    ProfileEvents['MergeTreeDataSelectorsSkippedMarks'] AS marks_skipped,
    if(ProfileEvents['SelectedMarks'] + ProfileEvents['MergeTreeDataSelectorsSkippedMarks'] > 0,
       round(ProfileEvents['MergeTreeDataSelectorsSkippedMarks'] * 100.0 / 
             (ProfileEvents['SelectedMarks'] + ProfileEvents['MergeTreeDataSelectorsSkippedMarks']), 2),
       0) AS skip_ratio_percent,
    round(read_rows / nullIf(ProfileEvents['SelectedMarks'], 0), 0) AS rows_per_mark,
    substring(query, 1, 120) AS query_preview
FROM system.query_log
WHERE 
    event_date = today()
    AND event_time >= now() - INTERVAL 600 MINUTE
    AND type = 'QueryFinish'
    AND query_duration_ms > 500
    AND query NOT LIKE '%system.query_log%'
ORDER BY query_duration_ms DESC
LIMIT 40
FORMAT PrettyCompact
"
```

#### 5. Get Full Query Text for Top Slow Queries - LOCAL

```bash
docker exec opik-clickhouse-1 clickhouse-client --user opik --password opik --query "
SELECT
    query_id,
    query_duration_ms,
    query
FROM system.query_log
WHERE 
    event_date = today()
    AND event_time >= now() - INTERVAL 600 MINUTE
    AND type = 'QueryFinish'
    AND query_duration_ms > 500
    AND query NOT LIKE '%system.query_log%'
ORDER BY query_duration_ms DESC
LIMIT 10
FORMAT Vertical
"
```

#### 6. EXPLAIN indexes=1 Analysis - LOCAL

After identifying slow queries, run EXPLAIN on them. Replace `<SLOW_QUERY_HERE>` with the actual query:

```bash
docker exec opik-clickhouse-1 clickhouse-client --user opik --password opik --query "
EXPLAIN indexes=1, actions=0
<SLOW_QUERY_HERE>
"
```

**Example for a specific slow query pattern:**

```bash
# For traces queries
docker exec opik-clickhouse-1 clickhouse-client --user opik --password opik --query "
EXPLAIN indexes=1
SELECT * FROM traces FINAL
WHERE workspace_id = '00000000-0000-0000-0000-000000000000'
  AND project_id = '00000000-0000-0000-0000-000000000000'
  AND created_at >= now() - INTERVAL 1 DAY
LIMIT 100
"
```

---

### For Production

Run via direct clickhouse-client (requires clickhouse-client installed locally or via brew/apt):

**Setup**: Export these environment variables before running (or replace inline):
```bash
export CLICKHOUSE_PROD_HOST="your-prod-host.clickhouse.cloud"
export CLICKHOUSE_PROD_USER="your-username"
export CLICKHOUSE_PROD_PASSWORD="your-password"
```

#### 1. Slow Queries Analysis (main query) - PROD

```bash
curl -s --max-time 60 "http://${CLICKHOUSE_PROD_HOST}:8123/" \
  -u "${CLICKHOUSE_PROD_USER}:${CLICKHOUSE_PROD_PASSWORD}" \
  --data "SELECT
    query_id,
    user,
    query_start_time,
    query_duration_ms,
    read_rows,
    formatReadableSize(read_bytes) AS read_bytes,
    formatReadableSize(memory_usage) AS memory_usage,
    extract(query, 'workspace_id\\\\s*=\\\\s*''([0-9a-f-]{36})''') AS workspace_id,
    extract(query, 'project_id\\\\s*=\\\\s*''([0-9a-f-]{36})''') AS project_id,
    substring(query, 1, 200) AS query_preview
FROM system.query_log
WHERE 
    event_date = today()
    AND event_time >= now() - INTERVAL 600 MINUTE
    AND type IN ('QueryFinish', 'ExceptionWhileProcessing')
    AND query_duration_ms > 100
    AND query NOT LIKE '%system.query_log%'
ORDER BY query_duration_ms DESC
LIMIT 30
FORMAT PrettyCompact"
```

#### 2. Granules Analysis - PROD

```bash
curl -s --max-time 60 "http://${CLICKHOUSE_PROD_HOST}:8123/" \
  -u "${CLICKHOUSE_PROD_USER}:${CLICKHOUSE_PROD_PASSWORD}" \
  --data "SELECT
    query_id,
    query_start_time,
    query_duration_ms,
    read_rows,
    ProfileEvents['SelectedMarks'] AS selected_marks,
    ProfileEvents['SelectedParts'] AS selected_parts,
    substring(query, 1, 150) AS query_preview
FROM system.query_log
WHERE 
    event_date = today()
    AND event_time >= now() - INTERVAL 600 MINUTE
    AND type = 'QueryFinish'
    AND query_duration_ms > 100
    AND query NOT LIKE '%system.query_log%'
ORDER BY query_duration_ms DESC
LIMIT 20
FORMAT PrettyCompact"
```

#### 3. Index Efficiency Ratio - PROD

```bash
curl -s --max-time 60 "http://${CLICKHOUSE_PROD_HOST}:8123/" \
  -u "${CLICKHOUSE_PROD_USER}:${CLICKHOUSE_PROD_PASSWORD}" \
  --data "SELECT
    query_id,
    query_duration_ms,
    ProfileEvents['SelectedMarks'] AS marks_read,
    ProfileEvents['MergeTreeDataSelectorsSkippedMarks'] AS marks_skipped,
    if(ProfileEvents['SelectedMarks'] + ProfileEvents['MergeTreeDataSelectorsSkippedMarks'] > 0,
       round(ProfileEvents['MergeTreeDataSelectorsSkippedMarks'] * 100.0 / 
             (ProfileEvents['SelectedMarks'] + ProfileEvents['MergeTreeDataSelectorsSkippedMarks']), 2),
       0) AS skip_ratio_percent,
    substring(query, 1, 100) AS query_preview
FROM system.query_log
WHERE 
    event_date = today()
    AND event_time >= now() - INTERVAL 600 MINUTE
    AND type = 'QueryFinish'
    AND query_duration_ms > 50
    AND query NOT LIKE '%system.query_log%'
ORDER BY skip_ratio_percent ASC, query_duration_ms DESC
LIMIT 20
FORMAT PrettyCompact"
```

#### 4. Detailed Granules Breakdown - PROD (ALWAYS RUN THIS)

```bash
curl -s --max-time 120 "http://${CLICKHOUSE_PROD_HOST}:8123/" \
  -u "${CLICKHOUSE_PROD_USER}:${CLICKHOUSE_PROD_PASSWORD}" \
  --data "SELECT
    query_id,
    query_start_time,
    query_duration_ms,
    read_rows,
    formatReadableSize(read_bytes) AS read_bytes,
    ProfileEvents['SelectedMarks'] AS selected_marks,
    ProfileEvents['SelectedParts'] AS selected_parts,
    ProfileEvents['SelectedRanges'] AS selected_ranges,
    ProfileEvents['MergeTreeDataSelectorsSelectedMarks'] AS marks_by_index,
    ProfileEvents['MergeTreeDataSelectorsSkippedMarks'] AS marks_skipped,
    if(ProfileEvents['SelectedMarks'] + ProfileEvents['MergeTreeDataSelectorsSkippedMarks'] > 0,
       round(ProfileEvents['MergeTreeDataSelectorsSkippedMarks'] * 100.0 / 
             (ProfileEvents['SelectedMarks'] + ProfileEvents['MergeTreeDataSelectorsSkippedMarks']), 2),
       0) AS skip_ratio_percent,
    round(read_rows / nullIf(ProfileEvents['SelectedMarks'], 0), 0) AS rows_per_mark,
    substring(query, 1, 120) AS query_preview
FROM system.query_log
WHERE 
    event_date = today()
    AND event_time >= now() - INTERVAL 600 MINUTE
    AND type = 'QueryFinish'
    AND query_duration_ms > 500
    AND query NOT LIKE '%system.query_log%'
ORDER BY query_duration_ms DESC
LIMIT 40
FORMAT PrettyCompact"
```

#### 5. Get Full Query Text for Top Slow Queries - PROD

```bash
curl -s --max-time 60 "http://${CLICKHOUSE_PROD_HOST}:8123/" \
  -u "${CLICKHOUSE_PROD_USER}:${CLICKHOUSE_PROD_PASSWORD}" \
  --data "SELECT
    query_id,
    query_duration_ms,
    query
FROM system.query_log
WHERE 
    event_date = today()
    AND event_time >= now() - INTERVAL 600 MINUTE
    AND type = 'QueryFinish'
    AND query_duration_ms > 500
    AND query NOT LIKE '%system.query_log%'
ORDER BY query_duration_ms DESC
LIMIT 10
FORMAT Vertical"
```

#### 6. EXPLAIN indexes=1 Analysis - PROD

After identifying slow queries, run EXPLAIN on them. Replace `<SLOW_QUERY_HERE>` with the actual query:

```bash
curl -s --max-time 60 "http://${CLICKHOUSE_PROD_HOST}:8123/" \
  -u "${CLICKHOUSE_PROD_USER}:${CLICKHOUSE_PROD_PASSWORD}" \
  --data "EXPLAIN indexes=1, actions=0
<SLOW_QUERY_HERE>"
```

**Example for a specific slow query pattern:**

```bash
# For traces queries
curl -s --max-time 60 "http://${CLICKHOUSE_PROD_HOST}:8123/" \
  -u "${CLICKHOUSE_PROD_USER}:${CLICKHOUSE_PROD_PASSWORD}" \
  --data "EXPLAIN indexes=1
SELECT * FROM traces FINAL
WHERE workspace_id = '00000000-0000-0000-0000-000000000000'
  AND project_id = '00000000-0000-0000-0000-000000000000'
  AND created_at >= now() - INTERVAL 1 DAY
LIMIT 100"
```

---

**Tips**:
- Adjust `query_duration_ms > 100` threshold as needed (use 1000 for truly slow queries, 50 for more granular analysis)
- Time window is set to last 600 MINUTEs. Modify `INTERVAL 600 MINUTE` as needed (e.g., `INTERVAL 24 HOUR` for last day)
- For prod, ensure environment variables are set: `CLICKHOUSE_PROD_HOST`, `CLICKHOUSE_PROD_USER`, `CLICKHOUSE_PROD_PASSWORD`

## Export Results to Markdown

After running the analysis, export results to a timestamped markdown file:

```bash
# Output file path
export REPORT_FILE="/tmp/slow-queries-analysis-$(date +%Y%m%d-%H%M%S).md"
echo "Report will be saved to: $REPORT_FILE"
```

The agent should automatically save the analysis results to this file after each run, including:
- Timestamp of analysis
- Environment (local/prod)
- Slow queries table with workspace_id and project_id
- **Granules breakdown with x/y format** (selected/total granules and parts)
- **EXPLAIN indexes=1 output** for top slow queries showing parts and granules filtering
- Source code mappings
- Recommendations based on parts/granules analysis

## Output Interpretation

- **query_duration_ms**: Total execution time in milliseconds (>1000ms is slow)
- **read_rows**: Number of rows read from storage
- **read_bytes**: Amount of data read from storage
- **memory_usage**: Peak memory consumption during query execution
- **type**: `QueryFinish` = successful, `ExceptionWhileProcessing` = failed

## Recommendations

After identifying slow queries, consider:
1. Adding appropriate indexes
2. Optimizing WHERE clauses to use primary key columns
3. Reducing the amount of data scanned
4. Using materialized views for complex aggregations
5. Checking if queries can benefit from PREWHERE optimization

## Results Summary Template

After running the analysis, present results in this format:

### Slow Queries Found

| Rank | Duration | Memory | Workspace ID | Project ID | Query Type | Source File |
|------|----------|--------|--------------|------------|------------|-------------|
| 1 | Xms | X MiB | `uuid` | `uuid` | `query_pattern` | `path/to/DAO.java:LINE` |

### Granules Breakdown (CRITICAL)

**Always include this section** - it reveals index efficiency issues that cause slow queries.

Run the detailed granules query and present results in this format:

| Query Type | Duration | Read Rows | Read Size | Granules (Selected/Total) | Parts | Ranges | Skip Ratio | Rows/Mark | Assessment |
|------------|----------|-----------|-----------|---------------------------|-------|--------|------------|-----------|------------|
| `spans_agg` | 30,482ms | 146.7M | 30.58 GiB | 187,450 / 187,450 (100%) | 226 | 1,234 | **0%** | 783 | ❌ FULL SCAN |
| `dataset_item_final` | 19,594ms | 313K | 1.42 GiB | 78 / 78 (100%) | 76 | 89 | **0%** | 4,016 | ❌ FULL SCAN |
| `feedback_scores` | 7,708ms | 8.1M | 6.08 GiB | 8,422 / 8,422 (100%) | 172 | 456 | **0%** | 966 | ❌ FULL SCAN |
| `traces_optimized` | 245ms | 12.5K | 45 MiB | 5 / 1,234 (0.4%) | 8 | 5 | **99.6%** | 2,500 | ✅ EXCELLENT |

#### Granules Metrics Explanation

| Metric | Description | What to Look For |
|--------|-------------|------------------|
| **Granules (Selected/Total)** | `selected_marks / (selected_marks + marks_skipped)` | Shows how many granules were read vs filtered. Format: "5 / 1000 (0.5%)" means only 0.5% of data was scanned |
| **Selected Marks** | Number of index marks (granules) actually read | Lower is better - high numbers indicate full scans |
| **Total Marks** | `selected_marks + marks_skipped` | Total granules available - shows table size |
| **Selected Parts** | Number of data parts accessed | High numbers may indicate fragmentation - consider OPTIMIZE TABLE |
| **Selected Ranges** | Number of continuous ranges read | More ranges = more random I/O - prefer fewer contiguous ranges |
| **Marks Skipped by Index** | Granules filtered out by primary key | **Should be > 0** - if 0, index not helping at all |
| **Skip Ratio %** | `marks_skipped / (selected_marks + marks_skipped) * 100` | **Should be > 50%** - higher is better. Inverse of selection percentage |
| **Rows per Mark** | `read_rows / selected_marks` | ~8192 is default granule size - higher values may indicate data skew |

#### Skip Ratio Interpretation

| Skip Ratio | Assessment | Action Required |
|------------|------------|-----------------|
| **0%** | ❌ Critical - Full table scan | Primary key not matching query filters |
| **1-30%** | ⚠️ Poor - Minimal filtering | Consider reordering primary key columns |
| **30-70%** | 🟡 Acceptable - Partial filtering | May benefit from secondary indexes |
| **70-90%** | ✅ Good - Effective filtering | Index working well |
| **>90%** | ✅ Excellent - Optimal | Index is highly selective |

#### Common Issues Identified by Granules Analysis

1. **Skip Ratio = 0%**: Query filters don't match primary key order
   - **Fix**: Reorder primary key to match WHERE clause patterns
   - **Example**: If querying by `workspace_id, project_id`, ensure primary key starts with these

2. **High Selected Marks with Low Rows**: Reading many granules for few rows
   - **Fix**: Add more selective columns to primary key prefix
   - **Example**: Add `created_at` for time-range queries

3. **Many Selected Parts**: Data fragmentation
   - **Fix**: Run `OPTIMIZE TABLE ... FINAL` or adjust merge settings

4. **High Rows per Mark (>10000)**: Possible data skew
   - **Fix**: Check data distribution, consider partitioning

### EXPLAIN indexes=1 Output Analysis

After identifying slow queries, run `EXPLAIN indexes=1` to understand index usage:

#### EXPLAIN Output Format

```
ReadFromMergeTree (table_name)
  Indexes:
    PrimaryKey
      Keys: workspace_id, project_id, created_at
      Condition: (workspace_id = 'xxx') AND (project_id = 'yyy')
      Parts: 12/156  ← Selected/Total parts (7.7% of parts)
      Granules: 45/98765  ← Selected/Total granules (0.045% of data)
```

#### Parts Analysis Interpretation

| Parts Ratio | Assessment | Action |
|-------------|------------|--------|
| **< 10%** | ✅ Excellent - Index filtering parts effectively | No action needed |
| **10-30%** | 🟡 Good - Reasonable filtering | Monitor performance |
| **30-60%** | ⚠️ Moderate - Index partially effective | Consider partition key changes |
| **60-90%** | ⚠️ Poor - Minimal part filtering | Review table structure and indexes |
| **> 90%** | ❌ Critical - Almost all parts scanned | Primary key not aligned with query pattern |

#### Granules Analysis Interpretation

| Granules Ratio | Assessment | Action |
|----------------|------------|--------|
| **< 1%** | ✅ Excellent - Highly selective | Optimal query performance |
| **1-5%** | ✅ Good - Effective filtering | Good performance expected |
| **5-20%** | 🟡 Acceptable - Moderate filtering | May benefit from optimization |
| **20-50%** | ⚠️ Poor - Scanning large portion | Consider index improvements |
| **> 50%** | ❌ Critical - Majority of data scanned | Index not effective for this query |

#### Example EXPLAIN Analysis

**Query with Good Index Usage:**
```
Parts: 3/450 (0.67%)
Granules: 127/1,234,567 (0.01%)
Assessment: ✅ Excellent - Primary key effectively filtering data
```

**Query with Poor Index Usage:**
```
Parts: 423/450 (94%)
Granules: 1,180,000/1,234,567 (95.6%)
Assessment: ❌ Critical - Full table scan, primary key not matching WHERE clause
Recommendation: Reorder primary key to match query filters
```

### Source Code Locations

For each slow query, provide:
1. **File path**: Full path to the DAO/repository file
2. **Line number**: Where the query is defined (look for `private static final String`)
3. **Method**: Which method uses this query
4. **Link**: Clickable reference to the code

Example:
```
Query: queues_final (963ms, 159 MiB)
Source: apps/opik-backend/src/main/java/com/comet/opik/domain/AnnotationQueueDAO.java
Line: 188 (FIND query)
Method: find()
```

### EXPLAIN Analysis Results

For each of the top 3-5 slow queries, include EXPLAIN output:

**Query #1: [Query Type/Pattern]**
```
Source: apps/opik-backend/src/main/java/com/comet/opik/domain/SomeDAO.java:123
Duration: 5,432ms

EXPLAIN indexes=1 output:
ReadFromMergeTree (table_name)
  Indexes:
    PrimaryKey
      Keys: workspace_id, project_id, created_at
      Condition: (workspace_id = 'xxx') AND (...)
      Parts: 423/450 (94%)  ← ❌ Almost all parts scanned
      Granules: 187,450/187,450 (100%)  ← ❌ Full table scan

Assessment: ❌ CRITICAL - Primary key not aligned with query filters
```

### Action Items

For each slow query identified:
1. **Link to source code location** (file path and line number)
2. **Show EXPLAIN output** with parts x/y and granules x/y
3. **Explain why it's slow** (reference granules analysis and skip ratio)
4. **Calculate selection percentages**: Both parts % and granules %
5. **Suggest specific optimizations**:
   - If granules ratio > 50%: Primary key reordering needed
   - If parts ratio > 60%: Consider partition key changes
   - If skip ratio = 0%: Index not being used at all
   - If many parts with low data: Run OPTIMIZE TABLE

