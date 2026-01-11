# Opik Optimizer Technical Overview

This document provides a comprehensive technical overview of the Opik Optimizer backend implementation, including API endpoints, main classes, data models, database schema, and the logic for managing optimization runs.

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [API Endpoints](#api-endpoints)
3. [Main Classes](#main-classes)
4. [Data Models](#data-models)
5. [Database Schema](#database-schema)
6. [Data Flow and Lifecycle](#data-flow-and-lifecycle)
7. [Optimization Run Management](#optimization-run-management)
8. [Integration with Experiments](#integration-with-experiments)

---

## Architecture Overview

The Opik Optimizer backend follows a layered architecture:

```
┌─────────────────────────────────────────────────────────┐
│              API Layer (REST Resources)                │
│         OptimizationsResource (JAX-RS)                  │
└────────────────────┬────────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────────┐
│           Service Layer (Business Logic)                │
│         OptimizationService (Reactive)                  │
└────────────────────┬────────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────────┐
│            Data Access Layer (DAO)                       │
│         OptimizationDAO (ClickHouse)                     │
└────────────────────┬────────────────────────────────────┘
                     │
┌────────────────────▼────────────────────────────────────┐
│              Database (ClickHouse)                       │
│         optimizations table (Analytics DB)               │
└─────────────────────────────────────────────────────────┘
```

The system uses:
- **Dropwizard** for the REST API framework
- **Reactor** (Project Reactor) for reactive, non-blocking operations
- **ClickHouse** for analytics data storage
- **JDBI3** for database access
- **Event Bus** (Google Guava) for event-driven updates

---

## API Endpoints

All optimization endpoints are under `/v1/private/optimizations`:

### Base Path
```java
@Path("/v1/private/optimizations")
```

### Endpoints

#### 1. Create Optimization
**POST** `/v1/private/optimizations`

Creates a new optimization run. If an ID is provided, it will be used; otherwise, a new UUID is generated.

```48:80:apps/opik-backend/src/main/java/com/comet/opik/api/resources/v1/priv/OptimizationsResource.java
@Path("/v1/private/optimizations")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Timed
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Tag(name = "Optimizations", description = "Optimization resources")
public class OptimizationsResource {

    private final @NonNull OptimizationService optimizationService;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;

    @PUT
    @Operation(operationId = "upsertOptimization", summary = "Upsert optimization", description = "Upsert optimization", responses = {
            @ApiResponse(responseCode = "201", description = "Created", headers = {
                    @Header(name = "Location", required = true, example = "${basePath}/v1/private/optimizations/{id}", schema = @Schema(implementation = String.class))})})
    @RateLimited
    public Response upsert(
            @RequestBody(content = @Content(schema = @Schema(implementation = Optimization.class))) @JsonView(Optimization.View.Write.class) @NotNull @Valid Optimization optimization,
            @Context UriInfo uriInfo) {
        var workspaceId = requestContext.get().getWorkspaceId();
        log.info("Upserting optimization with id '{}', name '{}', datasetName '{}', workspaceId '{}'",
                optimization.id(), optimization.name(), optimization.datasetName(), workspaceId);
        var id = optimizationService.upsert(optimization)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        var uri = uriInfo.getAbsolutePathBuilder().path("/%s".formatted(id)).build();
        log.info("Upserted optimization with id '{}', name '{}', datasetName '{}', workspaceId '{}'",
                id, optimization.name(), optimization.datasetName(), workspaceId);

        return Response.created(uri).build();
    }
```

**Request Body:**
```json
{
  "id": "uuid-v7-optional",
  "name": "Optimization Name",
  "dataset_name": "my-dataset",
  "objective_name": "accuracy",
  "status": "running",
  "metadata": { ... }
}
```

**Response:** `201 Created` with `Location` header pointing to the created optimization

#### 2. Upsert Optimization
**PUT** `/v1/private/optimizations`

Same as create, but uses PUT method. Both POST and PUT call the same `upsert` service method.

#### 3. Get Optimization by ID
**GET** `/v1/private/optimizations/{id}`

Retrieves a single optimization by its ID.

```82:125:apps/opik-backend/src/main/java/com/comet/opik/api/resources/v1/priv/OptimizationsResource.java
    @GET
    @Operation(operationId = "findOptimizations",
```

**Response:** Optimization object with enriched data (dataset name, num_trials, feedback_scores)

#### 4. Find Optimizations
**GET** `/v1/private/optimizations`

Searches for optimizations with pagination and filtering.

**Query Parameters:**
- `page`: Page number (default: 1)
- `size`: Page size
- `name`: Filter by name (partial match)
- `dataset_id`: Filter by dataset ID
- `dataset_deleted`: Filter by dataset deletion status

**Response:** `OptimizationPage` with paginated results

#### 5. Update Optimization
**PUT** `/v1/private/optimizations/{id}`

Updates an existing optimization (name and/or status).

```162:177:apps/opik-backend/src/main/java/com/comet/opik/api/resources/v1/priv/OptimizationsResource.java
    @PUT
    @Path("/{id}")
    @Operation(operationId = "updateOptimizationsById", summary = "Update optimization by id", description = "Update optimization by id", responses = {
            @ApiResponse(responseCode = "204", description = "No content")})
    public Response updateOptimizationsById(@PathParam("id") UUID id,
            @RequestBody(content = @Content(schema = @Schema(implementation = OptimizationUpdate.class))) @NotNull OptimizationUpdate request) {
        log.info("Update optimization with id '{}', with request '{}'", id, request);

        optimizationService.update(id, request)
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();

        log.info("Updates optimization with id '{}'", id);

        return Response.noContent().build();
    }
```

**Request Body:**
```json
{
  "name": "Updated Name",
  "status": "completed"
}
```

**Response:** `204 No Content`

#### 6. Delete Optimizations
**POST** `/v1/private/optimizations/delete`

Deletes one or more optimizations by ID.

```148:160:apps/opik-backend/src/main/java/com/comet/opik/api/resources/v1/priv/OptimizationsResource.java
    @POST
    @Path("/delete")
    @Operation
            @ApiResponse(responseCode = "204", description = "No content")})
    public Response deleteOptimizationsById(
            @RequestBody(content = @Content(schema = @Schema(implementation = DeleteIdsHolder.class))) @NotNull @Valid DeleteIdsHolder request) {
        log.info("Deleting optimizations, count '{}'", request.ids().size());
        optimizationService.delete(request.ids())
                .contextWrite(ctx -> setRequestContext(ctx, requestContext))
                .block();
        log.info("Deleted optimizations, count '{}'", request.ids().size());
        return Response.noContent().build();
    }
```

**Request Body:**
```json
{
  "ids": ["uuid1", "uuid2", ...]
}
```

**Response:** `204 No Content`

---

## Main Classes

### 1. OptimizationsResource

**Location:** `apps/opik-backend/src/main/java/com/comet/opik/api/resources/v1/priv/OptimizationsResource.java`

**Purpose:** REST API resource handling HTTP requests for optimization operations.

**Key Responsibilities:**
- Validates incoming requests
- Extracts workspace context from request headers
- Delegates business logic to `OptimizationService`
- Returns appropriate HTTP responses
- Handles rate limiting

**Dependencies:**
- `OptimizationService`: Business logic layer
- `RequestContext`: Workspace and user context provider
- `IdGenerator`: UUID generation

### 2. OptimizationService

**Location:** `apps/opik-backend/src/main/java/com/comet/opik/domain/OptimizationService.java`

**Purpose:** Business logic layer for optimization operations.

**Interface Methods:**
```java
Mono<UUID> upsert(Optimization optimization);
Mono<Optimization> getById(UUID id);
Mono<Optimization.OptimizationPage> find(int page, int size, OptimizationSearchCriteria searchCriteria);
Mono<Void> delete(Set<UUID> ids);
Flux<DatasetLastOptimizationCreated> getMostRecentCreatedOptimizationFromDatasets(Set<UUID> datasetIds);
Mono<Long> update(UUID id, OptimizationUpdate update);
Mono<Long> updateDatasetDeleted(Set<UUID> datasetIds);
```

**Key Responsibilities:**
- Generates optimization IDs if not provided
- Creates or retrieves associated datasets
- Enriches optimization data (dataset names, trial counts, feedback scores)
- Posts events for optimization lifecycle (created, deleted)
- Handles error cases (duplicate IDs, etc.)

**Implementation Highlights:**

**Upsert Logic:**
```94:117:apps/opik-backend/src/main/java/com/comet/opik/domain/OptimizationService.java
    @Override
    @WithSpan
    public Mono<UUID> upsert(@NonNull Optimization optimization) {
        UUID id = optimization.id() == null ? idGenerator.generateId() : optimization.id();
        IdGenerator.validateVersion(id, "Optimization");
        var name = StringUtils.getIfBlank(optimization.name(), nameGenerator::generateName);

        return datasetService.getOrCreateDataset(optimization.datasetName())
                .flatMap(datasetId -> {
                    var newOptimization = optimization.toBuilder()
                            .id(id)
                            .name(name)
                            .datasetId(datasetId)
                            .build();

                    return makeMonoContextAware((userName, workspaceId) -> optimizationDAO.upsert(newOptimization)
                            .thenReturn(newOptimization.id())
                            // The event is posted only when the experiment is successfully created.
                            .doOnSuccess(experimentId -> postOptimizationCreatedEvent(newOptimization, workspaceId,
                                    userName)))
                            .subscribeOn(Schedulers.boundedElastic());
                })
                // If a conflict occurs, we just return the id of the existing experiment.
                // If any other error occurs, we throw it. The event is not posted for both cases.
                .onErrorResume(throwable -> handleCreateError(throwable, id));
    }
```

**Enrichment Logic:**
```187:200:apps/opik-backend/src/main/java/com/comet/opik/domain/OptimizationService.java
    private List<Optimization> enrichOptimizations(List<Optimization> optimizations, String workspaceId) {
        var ids = optimizations.stream().map(Optimization::datasetId).collect(Collectors.toUnmodifiableSet());
        var datasetMap = datasetService.findByIds(ids, workspaceId)
                .stream().collect(Collectors.toMap(Dataset::id, Function.identity()));

        return optimizations.stream()
                .map(optimization -> optimization.toBuilder()
                        .datasetName(Optional
                                .ofNullable(datasetMap.get(optimization.datasetId()))
                                .map(Dataset::name)
                                .orElse(null))
                        .build())
                .toList();
    }
```

### 3. OptimizationDAO

**Location:** `apps/opik-backend/src/main/java/com/comet/opik/domain/OptimizationDAO.java`

**Purpose:** Data access layer for ClickHouse database operations.

**Key Responsibilities:**
- Executes SQL queries against ClickHouse
- Maps database rows to `Optimization` objects
- Handles ClickHouse-specific features (ReplacingMergeTree, FINAL queries)
- Aggregates related data (experiments, feedback scores)

**Implementation Highlights:**

**Upsert Query:**
```74:100:apps/opik-backend/src/main/java/com/comet/opik/domain/OptimizationDAO.java
    private static final String UPSERT = """
            INSERT INTO optimizations (
                id,
                dataset_id,
                name,
                workspace_id,
                objective_name,
                status,
                metadata,
                created_by,
                last_updated_by,
                last_updated_at
            )
            VALUES (
                :id,
                :dataset_id,
                :name,
                :workspace_id,
                :objective_name,
                :status,
                :metadata,
                :created_by,
                :last_updated_by,
                COALESCE(parseDateTime64BestEffortOrNull(:last_updated_at, 6), now64(6))
            )
            ;
            """;
```

**Complex Find Query with Aggregations:**
The `FIND` query is complex and includes:
- Optimization data from `optimizations` table
- Experiment counts (num_trials) from `experiments` table
- Feedback scores aggregated from `feedback_scores` and `authored_feedback_scores` tables
- Uses ClickHouse's `FINAL` keyword to get latest versions from ReplacingMergeTree

```102:232:apps/opik-backend/src/main/java/com/comet/opik/domain/OptimizationDAO.java
    private static final String FIND = """
            WITH optimization_final AS (
                SELECT
                    *
                FROM optimizations
                WHERE workspace_id = :workspace_id
                <if(id)>AND id = :id <endif>
                <if(name)>AND ilike(name, CONCAT('%%', :name ,'%%'))<endif>
                <if(dataset_id)>AND dataset_id = :dataset_id <endif>
                <if(dataset_deleted)>AND dataset_deleted = :dataset_deleted<endif>
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), experiments_final AS (
                SELECT
                    id,
                    optimization_id
                FROM experiments
                WHERE workspace_id = :workspace_id
                AND optimization_id IN (SELECT id FROM optimization_final)
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), experiment_items_final AS (
                SELECT
                    DISTINCT
                        experiment_id,
                        trace_id
                FROM experiment_items
                WHERE workspace_id = :workspace_id
                AND experiment_id IN (SELECT id FROM experiments_final)
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), feedback_scores_combined_raw AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       last_updated_by AS author
                FROM feedback_scores FINAL
                WHERE entity_type = :entity_type
                  AND workspace_id = :workspace_id
                  AND entity_id IN (SELECT trace_id FROM experiment_items_final)
                UNION ALL
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       author
                FROM authored_feedback_scores FINAL
                WHERE entity_type = :entity_type
                  AND workspace_id = :workspace_id
                  AND entity_id IN (SELECT trace_id FROM experiment_items_final)
            ), feedback_scores_with_ranking AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       author,
                       ROW_NUMBER() OVER (
                           PARTITION BY workspace_id, project_id, entity_id, name, author
                           ORDER BY last_updated_at DESC
                       ) as rn
                FROM feedback_scores_combined_raw
            ), feedback_scores_combined AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value
                FROM feedback_scores_with_ranking
                WHERE rn = 1
            ), feedback_scores_combined_grouped AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    groupArray(value) AS values
                FROM feedback_scores_combined
                GROUP BY workspace_id, project_id, entity_id, name
            ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    IF(length(values) = 1, arrayElement(values, 1), toDecimal64(arrayAvg(values), 9)) AS value
                FROM feedback_scores_combined_grouped
            ), feedback_scores_agg AS (
                SELECT
                    experiment_id,
                    mapFromArrays(
                        groupArray(fs_avg.name),
                        groupArray(fs_avg.avg_value)
                    ) AS feedback_scores
                FROM (
                    SELECT
                        et.experiment_id,
                        fs.name,
                        avg(fs.value) AS avg_value
                    FROM experiment_items_final as et
                    LEFT JOIN (
                        SELECT
                            name,
                            entity_id AS trace_id,
                            value
                        FROM feedback_scores_final
                    ) fs ON fs.trace_id = et.trace_id
                    GROUP BY et.experiment_id, fs.name
                    HAVING length(fs.name) > 0
                ) as fs_avg
                GROUP BY experiment_id
            )
            SELECT
                o.*,
                o.id as id,
                COUNT(DISTINCT e.id) FILTER (WHERE e.id != '') AS num_trials,
                maxMap(fs.feedback_scores) AS feedback_scores
            FROM optimization_final AS o
            LEFT JOIN experiments_final AS e ON o.id = e.optimization_id
            LEFT JOIN feedback_scores_agg AS fs ON e.id = fs.experiment_id
            GROUP BY o.*
            ORDER BY o.id DESC
            <if(limit)> LIMIT :limit <endif> <if(offset)> OFFSET :offset <endif>
            ;
            """;
```

**Update Query (ClickHouse ReplacingMergeTree Pattern):**
```270:291:apps/opik-backend/src/main/java/com/comet/opik/domain/OptimizationDAO.java
    private static final String UPDATE_BY_ID = """
            INSERT INTO optimizations (
            	id, dataset_id, name, workspace_id, objective_name, status, metadata, created_at, created_by, last_updated_by
            )
            SELECT
                id,
                dataset_id,
                <if(name)> :name <else> name <endif> as name,
                workspace_id,
                objective_name,
                <if(status)> :status <else> status <endif> as status,
                metadata,
                created_at,
                created_by,
                :user_name as last_updated_by
            FROM optimizations
            WHERE id = :id
            AND workspace_id = :workspace_id
            ORDER BY id DESC, last_updated_at DESC
            LIMIT 1
            ;
            """;
```

Note: ClickHouse uses an insert-based update pattern for ReplacingMergeTree. Updates are performed by inserting a new row with the same primary key and updated values. The engine automatically keeps the latest version based on `last_updated_at`.

---

## Data Models

### 1. Optimization (API Model)

**Location:** `apps/opik-backend/src/main/java/com/comet/opik/api/Optimization.java`

**Structure:**
```22:40:apps/opik-backend/src/main/java/com/comet/opik/api/Optimization.java
public record Optimization(
        @JsonView( {
                Optimization.View.Public.class, Optimization.View.Write.class}) UUID id,
        @JsonView({Optimization.View.Public.class, Optimization.View.Write.class}) String name,
        @JsonView({Optimization.View.Public.class, Optimization.View.Write.class}) @NotBlank String datasetName,
        @JsonView({Optimization.View.Public.class, Optimization.View.Write.class}) @NotBlank String objectiveName,
        @JsonView({Optimization.View.Public.class, Optimization.View.Write.class}) @NotNull OptimizationStatus status,
        @Schema(implementation = JsonListString.class) @JsonView({Optimization.View.Public.class,
                Optimization.View.Write.class}) JsonNode metadata,
        @JsonView({Optimization.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) UUID datasetId,
        @JsonView({Optimization.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Long numTrials,
        @JsonView({
                Optimization.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) List<FeedbackScoreAverage> feedbackScores,
        @JsonView({Optimization.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) Instant createdAt,
        @JsonView({Optimization.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String createdBy,
        @JsonView({Optimization.View.Public.class,
                Optimization.View.Write.class}) @InRange(before = InRangeValidator.MAX_ANALYTICS_DB) Instant lastUpdatedAt,
        @JsonView({
                Optimization.View.Public.class}) @Schema(accessMode = Schema.AccessMode.READ_ONLY) String lastUpdatedBy){
```

**Fields:**
- `id`: UUID (UUIDv7 format)
- `name`: Optional name for the optimization
- `datasetName`: Name of the dataset (write-only, converted to datasetId)
- `datasetId`: UUID of the dataset (read-only, computed)
- `objectiveName`: Name of the metric/objective being optimized
- `status`: Current status (`RUNNING`, `COMPLETED`, `CANCELLED`)
- `metadata`: JSON metadata (optimizer config, parameters, etc.)
- `numTrials`: Number of experiments/trials (read-only, computed from experiments table)
- `feedbackScores`: Aggregated feedback scores (read-only, computed)
- `createdAt`: Creation timestamp
- `createdBy`: User who created it
- `lastUpdatedAt`: Last update timestamp
- `lastUpdatedBy`: User who last updated it

**JSON Views:**
- `View.Public`: Fields visible in API responses
- `View.Write`: Fields that can be written in requests

### 2. OptimizationStatus

**Location:** `apps/opik-backend/src/main/java/com/comet/opik/api/OptimizationStatus.java`

**Enum Values:**
```12:27:apps/opik-backend/src/main/java/com/comet/opik/api/OptimizationStatus.java
public enum OptimizationStatus {
    RUNNING("running"),
    COMPLETED("completed"),
    CANCELLED("cancelled");

    @JsonValue
    private final String value;

    @JsonCreator
    public static OptimizationStatus fromString(String value) {
        return Arrays.stream(values())
                .filter(status -> status.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown Optimization Status '%s'".formatted(value)));
    }
}
```

### 3. OptimizationUpdate

**Location:** `apps/opik-backend/src/main/java/com/comet/opik/api/OptimizationUpdate.java`

**Structure:**
```11:12:apps/opik-backend/src/main/java/com/comet/opik/api/OptimizationUpdate.java
public record OptimizationUpdate(String name, OptimizationStatus status) {
}
```

Used for partial updates (only name and/or status can be updated).

### 4. OptimizationSearchCriteria

**Location:** `apps/opik-backend/src/main/java/com/comet/opik/domain/OptimizationSearchCriteria.java`

**Structure:**
```10:12:apps/opik-backend/src/main/java/com/comet/opik/domain/OptimizationSearchCriteria.java
public record OptimizationSearchCriteria(String name, UUID datasetId, @NonNull EntityType entityType,
        Boolean datasetDeleted, Collection<UUID> datasetIds) {
}
```

Used for filtering and searching optimizations.

---

## Database Schema

### ClickHouse Table: `optimizations`

**Location:** `apps/opik-backend/src/main/resources/liquibase/db-app-analytics/migrations/000023_fix_guardrails_optimizations_engine.sql`

**Schema:**
```34:52:apps/opik-backend/src/main/resources/liquibase/db-app-analytics/migrations/000023_fix_guardrails_optimizations_engine.sql
--changeset liyaka:000023-fix-optimizations-engine-02 id:create-optimizations-table
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.optimizations ON CLUSTER '{cluster}'
(
    `workspace_id` String,
    `dataset_id` FixedString(36),
    `id` FixedString(36),
    `name` String,
    `objective_name` String,
    `status` Enum8('running' = 0, 'completed' = 1, 'cancelled' = 2),
    `metadata` String,
    `created_at` DateTime64(9, 'UTC') DEFAULT now64(9),
    `last_updated_at` DateTime64(9, 'UTC') DEFAULT now64(9),
    `created_by` String,
    `last_updated_by` String,
    `dataset_deleted` Bool DEFAULT false
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/optimizations', '{replica}', last_updated_at)
ORDER BY (workspace_id, dataset_id, id)
SETTINGS index_granularity = 8192;
```

**Key Characteristics:**

1. **Engine: ReplicatedReplacingMergeTree**
   - Replicated across ClickHouse cluster
   - ReplacingMergeTree keeps only the latest version based on `last_updated_at`
   - Uses `/clickhouse/tables/{shard}/...` path for replication coordination

2. **Primary Key/Order By:**
   - `(workspace_id, dataset_id, id)`
   - Enables efficient queries by workspace, dataset, or specific optimization

3. **Fields:**
   - `workspace_id`: String - Workspace identifier
   - `dataset_id`: FixedString(36) - UUID of the dataset
   - `id`: FixedString(36) - UUID of the optimization (UUIDv7)
   - `name`: String - Optimization name
   - `objective_name`: String - Metric/objective name
   - `status`: Enum8 - Status enum (running=0, completed=1, cancelled=2)
   - `metadata`: String - JSON metadata (stored as string)
   - `created_at`: DateTime64(9, 'UTC') - Creation timestamp
   - `last_updated_at`: DateTime64(9, 'UTC') - Last update timestamp
   - `created_by`: String - Creator username
   - `last_updated_by`: String - Last updater username
   - `dataset_deleted`: Bool - Soft delete flag when dataset is deleted

4. **Index Granularity:** 8192 (default ClickHouse setting)

### Related Tables

#### `experiments` Table

Experiments are linked to optimizations via `optimization_id`:

```4:7:apps/opik-backend/src/main/resources/liquibase/db-app-analytics/migrations/000021_add_type_optimization_id_to_experiments.sql
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments
    ADD COLUMN IF NOT EXISTS optimization_id String,
    ADD COLUMN IF NOT EXISTS type ENUM('regular' = 0 , 'trial' = 1, 'mini-batch' = 2) DEFAULT 'regular';
```

- `optimization_id`: Links experiment to optimization
- `type`: Experiment type (`regular`, `trial`, `mini-batch`)
  - `trial`: An optimization trial/iteration
  - `mini-batch`: A mini-batch within an optimization
  - `regular`: Regular experiment (not part of optimization)

#### `experiment_items` Table

Links experiments to traces (evaluation runs).

#### `feedback_scores` and `authored_feedback_scores` Tables

Store evaluation scores for traces, which are aggregated to compute optimization-level feedback scores.

---

## Data Flow and Lifecycle

### 1. Optimization Creation Flow

```
┌─────────────┐
│   Client    │
│  (SDK/Python)│
└──────┬──────┘
       │ POST /v1/private/optimizations
       │ { dataset_name, objective_name, status: "running", metadata }
       ▼
┌─────────────────────┐
│ OptimizationsResource│
└──────┬──────────────┘
       │
       ▼
┌─────────────────────┐
│ OptimizationService  │
│  - Generate ID       │
│  - Get/Create Dataset│
│  - Build Optimization│
└──────┬──────────────┘
       │
       ▼
┌─────────────────────┐
│   OptimizationDAO    │
│   INSERT INTO        │
│   optimizations      │
└──────┬──────────────┘
       │
       ▼
┌─────────────────────┐
│   ClickHouse         │
│   optimizations table│
└──────┬──────────────┘
       │
       ▼
┌─────────────────────┐
│   Event Bus         │
│   OptimizationCreated│
└──────┬──────────────┘
       │
       ▼
┌─────────────────────┐
│ DatasetEventListener│
│  - Update dataset   │
│    last_optimization │
└─────────────────────┘
```

**Step-by-Step:**

1. **Client Request**: SDK calls `optimizer.optimize_prompt()` which internally calls `opik_client.create_optimization()`
2. **Resource Layer**: `OptimizationsResource.create()` receives request, extracts workspace context
3. **Service Layer**: `OptimizationService.upsert()`:
   - Generates UUID if not provided (UUIDv7)
   - Generates name if not provided
   - Gets or creates dataset via `DatasetService`
   - Builds complete `Optimization` object
4. **DAO Layer**: `OptimizationDAO.upsert()`:
   - Binds parameters to SQL statement
   - Inserts into ClickHouse
   - Uses reactive streams (Mono/Flux)
5. **Event Posting**: After successful insert, posts `OptimizationCreated` event
6. **Dataset Update**: `DatasetEventListener` receives event and updates dataset's `last_created_optimization_at`

**When Data is Inserted:**
- **On Creation**: All fields are inserted with initial values
- **On Update**: New row is inserted (ReplacingMergeTree pattern) with updated fields

### 2. Optimization Update Flow

```
┌─────────────┐
│   Client    │
└──────┬──────┘
       │ PUT /v1/private/optimizations/{id}
       │ { status: "completed" }
       ▼
┌─────────────────────┐
│ OptimizationsResource│
└──────┬──────────────┘
       │
       ▼
┌─────────────────────┐
│ OptimizationService  │
│  - Validate exists   │
│  - Build update      │
└──────┬──────────────┘
       │
       ▼
┌─────────────────────┐
│   OptimizationDAO    │
│   INSERT INTO        │
│   (ReplacingMergeTree)│
└──────┬──────────────┘
       │
       ▼
┌─────────────────────┐
│   ClickHouse         │
│   (keeps latest)     │
└─────────────────────┘
```

**Update Pattern:**
ClickHouse ReplacingMergeTree doesn't support traditional UPDATE statements. Instead, updates are performed by:

1. Selecting the current row
2. Inserting a new row with:
   - Same primary key (`workspace_id`, `dataset_id`, `id`)
   - Updated fields
   - New `last_updated_at` timestamp
3. ReplacingMergeTree automatically keeps the row with the latest `last_updated_at`

**Update Query Example:**
```622:628:apps/opik-backend/src/main/java/com/comet/opik/domain/OptimizationDAO.java
    private Flux<? extends Result> update(UUID id, OptimizationUpdate update, Connection connection) {
        var template = buildUpdateTemplate(update);

        var statement = createUpdateStatement(id, update, connection, template.render());

        return makeMonoContextAware(bindUserNameAndWorkspaceContextToStream(statement));
    }
```

### 3. Optimization Query Flow

```
┌─────────────┐
│   Client    │
└──────┬──────┘
       │ GET /v1/private/optimizations/{id}
       ▼
┌─────────────────────┐
│ OptimizationsResource│
└──────┬──────────────┘
       │
       ▼
┌─────────────────────┐
│ OptimizationService  │
│  - Get from DAO      │
│  - Enrich with       │
│    dataset name      │
└──────┬──────────────┘
       │
       ▼
┌─────────────────────┐
│   OptimizationDAO    │
│   Complex JOIN query │
│   - optimizations    │
│   - experiments      │
│   - feedback_scores  │
└──────┬──────────────┘
       │
       ▼
┌─────────────────────┐
│   ClickHouse         │
│   - Aggregates       │
│   - Computes         │
│     num_trials       │
│     feedback_scores  │
└──────┬──────────────┘
       │
       ▼
┌─────────────────────┐
│   Response           │
│   (enriched)         │
└─────────────────────┘
```

**Enrichment Process:**

The `FIND` query performs complex aggregations:

1. **Optimization Data**: Gets latest optimization row (using `LIMIT 1 BY id`)
2. **Experiment Count**: Counts distinct experiments linked via `optimization_id`
3. **Feedback Scores**: 
   - Joins through `experiments` → `experiment_items` → `traces`
   - Aggregates feedback scores from `feedback_scores` and `authored_feedback_scores`
   - Averages scores per experiment
   - Creates a map of metric names to average values

**When Data is Read:**
- **On GET by ID**: Full query with all aggregations
- **On FIND (list)**: Same query with pagination and filtering
- **ClickHouse FINAL**: Ensures latest versions from ReplacingMergeTree are used

### 4. Optimization Deletion Flow

```
┌─────────────┐
│   Client    │
└──────┬──────┘
       │ POST /v1/private/optimizations/delete
       │ { ids: [...] }
       ▼
┌─────────────────────┐
│ OptimizationsResource│
└──────┬──────────────┘
       │
       ▼
┌─────────────────────┐
│ OptimizationService  │
│  - Get dataset IDs   │
│  - Delete rows       │
└──────┬──────────────┘
       │
       ▼
┌─────────────────────┐
│   OptimizationDAO    │
│   DELETE FROM        │
│   optimizations      │
└──────┬──────────────┘
       │
       ▼
┌─────────────────────┐
│   ClickHouse         │
│   (deletes rows)     │
└──────┬──────────────┘
       │
       ▼
┌─────────────────────┐
│   Event Bus         │
│   OptimizationsDeleted│
└─────────────────────┘
```

**Deletion:**
- Physical deletion from ClickHouse (DELETE statement)
- Posts `OptimizationsDeleted` event with affected dataset IDs
- Note: Related experiments are NOT automatically deleted

---

## Optimization Run Management

### Lifecycle States

An optimization progresses through these states:

1. **RUNNING**: Optimization is actively running
   - Set when optimization is created
   - SDK is evaluating candidates
   - Experiments are being created with `optimization_id`

2. **COMPLETED**: Optimization finished successfully
   - Set when `optimizer.optimize_prompt()` completes
   - SDK calls `optimization.update(status="completed")`
   - All trials have been evaluated

3. **CANCELLED**: Optimization was cancelled
   - Set if optimization fails or is manually cancelled
   - SDK may call `optimization.update(status="cancelled")` on error

### Status Updates

**When Status is Updated:**

1. **Initial Creation**: Status set to `RUNNING` (or provided status)
   ```java
   // In OptimizationService.upsert()
   var newOptimization = optimization.toBuilder()
       .id(id)
       .name(name)
       .datasetId(datasetId)
       .build();
   ```

2. **During Optimization**: SDK can update status to track progress
   ```python
   # In SDK
   optimization.update(status="running")  # If needed
   ```

3. **On Completion**: SDK updates to `COMPLETED`
   ```python
   # In SDK after optimization finishes
   optimization.update(status="completed")
   ```

4. **On Error**: SDK may update to `CANCELLED`
   ```python
   # In SDK on exception
   optimization.update(status="cancelled")
   ```

**Update Implementation:**
```144:152:apps/opik-backend/src/main/java/com/comet/opik/domain/OptimizationService.java
    @Override
    public Mono<Long> update(@NonNull UUID id, @NonNull OptimizationUpdate update) {
        if (update.name() == null && update.status() == null) {
            return Mono.empty();
        }

        return optimizationDAO.getById(id)
                .switchIfEmpty(Mono.error(failWithNotFound("Optimization", id)))
                .then(Mono.defer(() -> optimizationDAO.update(id, update)));
    }
```

### Metadata Storage

The `metadata` field stores optimizer-specific configuration:

**Example Metadata:**
```json
{
  "optimizer": "EvolutionaryOptimizer",
  "population_size": 30,
  "num_generations": 15,
  "mutation_rate": 0.2,
  "crossover_rate": 0.8,
  "enable_moo": true,
  "model": "gpt-4o",
  "metric_name": "accuracy"
}
```

**When Metadata is Set:**
- On creation: SDK includes optimizer metadata
- Metadata is stored as JSON string in ClickHouse
- Can be queried but not directly updated (would require full optimization update)

### Trial Tracking

Trials are tracked through the `experiments` table:

1. **Each Trial = One Experiment**
   - Experiment has `optimization_id` set
   - Experiment has `type = 'trial'`
   - Experiment links to dataset via `dataset_id`

2. **Trial Count Calculation:**
   ```sql
   COUNT(DISTINCT e.id) FILTER (WHERE e.id != '') AS num_trials
   ```
   - Counts distinct experiments with matching `optimization_id`
   - Excludes empty IDs

3. **When Trials are Created:**
   - SDK creates experiment for each candidate evaluation
   - Sets `optimization_id` on experiment
   - Sets `type = 'trial'` or `type = 'mini-batch'`

---

## Integration with Experiments

### Relationship Model

```
Optimization (1) ──→ (N) Experiments
     │
     │ (via optimization_id)
     │
     ▼
Experiment (1) ──→ (N) ExperimentItems
     │
     │ (via experiment_id)
     │
     ▼
ExperimentItem (1) ──→ (1) Trace
     │
     │ (via trace_id)
     │
     ▼
Trace (1) ──→ (N) FeedbackScores
```

### Experiment Types

Experiments linked to optimizations have a `type` field:

- **`trial`**: A single optimization trial/candidate evaluation
- **`mini-batch`**: A mini-batch evaluation (used by some optimizers like GEPA)
- **`regular`**: Regular experiment (not part of optimization)

**Experiment Creation with Optimization:**
```727:735:apps/opik-backend/src/main/java/com/comet/opik/domain/ExperimentDAO.java
    private Publisher<? extends Result> insert(Experiment experiment, Connection connection) {
        var statement = connection.createStatement(INSERT)
                .bind("id", experiment.id())
                .bind("dataset_id", experiment.datasetId())
                .bind("name", experiment.name())
                .bind("metadata", getStringOrDefault(experiment.metadata()))
                .bind("type", Optional.ofNullable(experiment.type()).orElse(ExperimentType.REGULAR).getValue())
                .bind("optimization_id", experiment.optimizationId() != null ? experiment.optimizationId() : "")
                .bind("status", Optional.ofNullable(experiment.status()).orElse(ExperimentStatus.COMPLETED).getValue());
```

### Feedback Score Aggregation

Feedback scores flow from traces → experiments → optimizations:

1. **Trace Level**: Each trace (evaluation run) can have feedback scores
2. **Experiment Level**: Scores are aggregated per experiment
3. **Optimization Level**: Scores are aggregated across all experiments in the optimization

**Aggregation Query Logic:**
```133:219:apps/opik-backend/src/main/java/com/comet/opik/domain/OptimizationDAO.java
            ), feedback_scores_combined_raw AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       last_updated_by AS author
                FROM feedback_scores FINAL
                WHERE entity_type = :entity_type
                  AND workspace_id = :workspace_id
                  AND entity_id IN (SELECT trace_id FROM experiment_items_final)
                UNION ALL
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       author
                FROM authored_feedback_scores FINAL
                WHERE entity_type = :entity_type
                  AND workspace_id = :workspace_id
                  AND entity_id IN (SELECT trace_id FROM experiment_items_final)
            ), feedback_scores_with_ranking AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       author,
                       ROW_NUMBER() OVER (
                           PARTITION BY workspace_id, project_id, entity_id, name, author
                           ORDER BY last_updated_at DESC
                       ) as rn
                FROM feedback_scores_combined_raw
            ), feedback_scores_combined AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value
                FROM feedback_scores_with_ranking
                WHERE rn = 1
            ), feedback_scores_combined_grouped AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    groupArray(value) AS values
                FROM feedback_scores_combined
                GROUP BY workspace_id, project_id, entity_id, name
            ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    IF(length(values) = 1, arrayElement(values, 1), toDecimal64(arrayAvg(values), 9)) AS value
                FROM feedback_scores_combined_grouped
            ), feedback_scores_agg AS (
                SELECT
                    experiment_id,
                    mapFromArrays(
                        groupArray(fs_avg.name),
                        groupArray(fs_avg.avg_value)
                    ) AS feedback_scores
                FROM (
                    SELECT
                        et.experiment_id,
                        fs.name,
                        avg(fs.value) AS avg_value
                    FROM experiment_items_final as et
                    LEFT JOIN (
                        SELECT
                            name,
                            entity_id AS trace_id,
                            value
                        FROM feedback_scores_final
                    ) fs ON fs.trace_id = et.trace_id
                    GROUP BY et.experiment_id, fs.name
                    HAVING length(fs.name) > 0
                ) as fs_avg
                GROUP BY experiment_id
            )
```

**Steps:**
1. Get all traces from experiments in the optimization
2. Get feedback scores for those traces (from both `feedback_scores` and `authored_feedback_scores`)
3. Deduplicate scores (keep latest per author)
4. Average scores when multiple values exist for same metric
5. Aggregate per experiment
6. Create map of metric names to average values

---

## Key Implementation Details

### Reactive Programming

The backend uses **Project Reactor** for non-blocking, reactive operations:

- **Mono**: Represents 0 or 1 result
- **Flux**: Represents 0 to N results
- **Context**: Carries workspace/user context through reactive chain

**Example:**
```107:112:apps/opik-backend/src/main/java/com/comet/opik/domain/OptimizationService.java
                    return makeMonoContextAware((userName, workspaceId) -> optimizationDAO.upsert(newOptimization)
                            .thenReturn(newOptimization.id())
                            // The event is posted only when the experiment is successfully created.
                            .doOnSuccess(experimentId -> postOptimizationCreatedEvent(newOptimization, workspaceId,
                                    userName)))
                            .subscribeOn(Schedulers.boundedElastic());
```

### ClickHouse ReplacingMergeTree

**Why ReplacingMergeTree?**
- Handles eventual consistency in distributed systems
- Automatically deduplicates based on primary key
- Keeps latest version based on `last_updated_at`
- Efficient for append-only workloads

**Querying Latest Versions:**
- Use `FINAL` keyword: `SELECT * FROM optimizations FINAL`
- Or use `LIMIT 1 BY id`: `SELECT * FROM optimizations ORDER BY last_updated_at DESC LIMIT 1 BY id`

### Event-Driven Updates

**Events:**
1. **OptimizationCreated**: Posted when optimization is created
   - Triggers dataset `last_created_optimization_at` update
   - Used for tracking and UI display

2. **OptimizationsDeleted**: Posted when optimizations are deleted
   - Contains affected dataset IDs
   - Used for cleanup and notifications

**Event Listener:**
```64:73:apps/opik-backend/src/main/java/com/comet/opik/api/resources/v1/events/DatasetEventListener.java
    @Subscribe
    public void onOptimizationCreated(OptimizationCreated event) {
        log.info("Recording optimization with id '{}' for dataset '{}'", event.optimizationId(), event.datasetId());

        datasetService
                .recordOptimizations(Set.of(new DatasetLastOptimizationCreated(event.datasetId(), event.createdAt())))
                .contextWrite(ctx -> setContext(event, ctx))
                .block();

        log.info("Recorded optimization with id '{}' for dataset '{}'", event.optimizationId(), event.datasetId());
    }
```

### Workspace Isolation

All queries are workspace-scoped:
- `workspace_id` is extracted from request context
- All queries include `WHERE workspace_id = :workspace_id`
- Prevents cross-workspace data access

**Context Binding:**
```575:582:apps/opik-backend/src/main/java/com/comet/opik/domain/OptimizationDAO.java
        return makeFluxContextAware((userName, workspaceId) -> {
            log.info("Inserting optimization with id '{}', datasetId '{}', datasetName '{}', workspaceId '{}'",
                    optimization.id(), optimization.datasetId(), optimization.datasetName(), workspaceId);
            statement.bind("created_by", userName)
                    .bind("last_updated_by", userName)
                    .bind("workspace_id", workspaceId);
            return Flux.from(statement.execute());
        });
```

---

## Summary

The Opik Optimizer backend provides:

1. **REST API** for CRUD operations on optimizations
2. **Reactive Service Layer** for business logic and enrichment
3. **ClickHouse Storage** with ReplacingMergeTree for versioned data
4. **Event-Driven Updates** for dataset tracking
5. **Complex Aggregations** for trial counts and feedback scores
6. **Workspace Isolation** for multi-tenancy

The system is designed to handle:
- High-throughput optimization runs
- Real-time status updates
- Complex queries with aggregations
- Eventual consistency in distributed systems
- Multi-tenant isolation

For more information on the optimizer algorithms, see [OPTIMIZER_ALGORITHMS.md](OPTIMIZER_ALGORITHMS.md).
