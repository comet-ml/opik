# Plan: Dedicated Thread Pool for CSV Processing

## Overview
Create a dedicated Reactor `Scheduler` with its own thread pool specifically for CSV processing to prevent thread starvation and provide better resource isolation for long-running CSV operations.

## Goals
1. Isolate CSV processing from other background tasks
2. Configure appropriate thread pool size and queue capacity
3. Enable independent monitoring and tuning for CSV operations
4. Maintain existing reactive patterns with Reactor

## Implementation Steps

### 1. Create CSV Processing Configuration Class
**File**: `apps/opik-backend/src/main/java/com/comet/opik/infrastructure/CsvProcessingConfig.java`

**Purpose**: Define configuration parameters for CSV thread pool
- `threadPoolSize`: Number of threads (default: 4)
- `queueCapacity`: Maximum queued tasks (default: 100)
- `threadNamePrefix`: Thread naming for monitoring (default: "csv-processor-")

**Configuration Source**: `apps/opik-backend/config.yml` (new section `csvProcessingConfig`)

### 2. Create CSV Thread Pool Provider
**File**: `apps/opik-backend/src/main/java/com/comet/opik/infrastructure/csv/CsvProcessingModule.java`

**Purpose**: Guice module to provide dedicated Scheduler
- Create `Scheduler` using `Schedulers.newBoundedElastic()` with custom config
- Configure thread pool size, queue capacity, and thread factory
- Ensure proper lifecycle management (shutdown on application stop)
- Bind with qualifier annotation `@Named("csvProcessingScheduler")`

### 3. Update DatasetCsvProcessorService
**File**: `apps/opik-backend/src/main/java/com/comet/opik/domain/DatasetCsvProcessorService.java`

**Changes**:
- Inject `@Named("csvProcessingScheduler") Scheduler` into `DatasetCsvProcessorServiceImpl`
- Replace `Schedulers.boundedElastic()` with injected scheduler in `processCsvAsync()`
- Keep existing logic unchanged

### 4. Register Guice Module
**File**: `apps/opik-backend/src/main/java/com/comet/opik/OpikApplication.java`

**Changes**:
- Add `CsvProcessingModule` to Guice module registration
- Ensure module is initialized during application startup

### 5. Add Configuration to config.yml
**File**: `apps/opik-backend/config.yml`

**New Section**:
```yaml
csvProcessingConfig:
  threadPoolSize: ${CSV_PROCESSING_THREADS:-4}
  queueCapacity: ${CSV_PROCESSING_QUEUE:-100}
  threadNamePrefix: "csv-processor-"
```

## File Structure
```
apps/opik-backend/
├── src/main/java/com/comet/opik/
│   ├── infrastructure/
│   │   ├── CsvProcessingConfig.java (NEW)
│   │   └── csv/
│   │       └── CsvProcessingModule.java (NEW)
│   ├── domain/
│   │   └── DatasetCsvProcessorService.java (MODIFY)
│   └── OpikApplication.java (MODIFY)
└── config.yml (MODIFY)
```

## Configuration Recommendations

### Thread Pool Size
- **Default**: 4 threads
- **Reasoning**: CSV processing is I/O-bound (S3 download, DB writes) but also CPU-intensive (parsing)
- **Tuning**: Can be increased for high-volume environments

### Queue Capacity
- **Default**: 100 tasks
- **Reasoning**: Prevent memory exhaustion from excessive queued files
- **Behavior**: Rejects new tasks when queue is full (fail-fast)

## Benefits of This Approach

1. **Isolation**: CSV processing won't compete with attachment operations or other bounded elastic tasks
2. **Observability**: Dedicated thread naming makes monitoring easier (`csv-processor-1`, `csv-processor-2`, etc.)
3. **Tunable**: Can adjust thread count independently based on CSV workload
4. **Fail-Fast**: Queue capacity prevents memory issues from excessive queued files
5. **Minimal Impact**: No changes to existing attachment processing or other services

## Testing Strategy

1. **Unit Tests**: Mock the scheduler injection in `DatasetCsvProcessorServiceImpl`
2. **Integration Tests**: Verify CSV processing still works with new scheduler
3. **Load Tests**: Upload multiple large CSV files simultaneously to verify:
   - Thread pool handles concurrent uploads
   - Queue capacity is respected
   - No impact on other backend operations
4. **Monitoring**: Check thread names in logs/metrics to confirm dedicated pool usage

## Rollout Considerations

1. **Backward Compatibility**: Default configuration maintains current behavior
2. **Environment Variables**: Allow runtime configuration via env vars
3. **Documentation**: Update `BACKEND_CSV_PROCESSING_IMPLEMENTATION.md` with new threading model
4. **Metrics**: Consider adding metrics for queue depth and thread utilization (future enhancement)

## Alternative Considered (Not Recommended)

Using Java `ExecutorService` directly instead of Reactor `Scheduler`:
- **Con**: Breaks reactive patterns, requires more code changes
- **Con**: Loses Reactor's context propagation (RequestContext)
- **Con**: More complex error handling and cancellation

## Estimated Impact

- **Files Created**: 2
- **Files Modified**: 3
- **Lines Changed**: ~150-200
- **Risk Level**: Low (isolated change, no existing logic modified)
- **Testing Effort**: Medium (need load testing to validate)

## Current Thread Pool Usage

### Shared `boundedElastic` Scheduler (Current State)
The following services currently share the default `Schedulers.boundedElastic()` thread pool:

1. **DatasetCsvProcessorService**: CSV file processing (long-running, I/O + CPU intensive)
2. **AttachmentStripperService**: Stripping attachments from traces/spans
3. **AttachmentReinjectorService**: Downloading and reinjecting attachments

### Problem
Long-running CSV processing tasks can starve attachment operations and other background tasks, leading to:
- Delayed attachment processing
- Reduced system responsiveness
- Unpredictable performance under load

### Solution
Dedicated thread pool for CSV processing ensures resource isolation and prevents starvation.

---

**Status**: Plan ready for implementation
**Next Steps**: Await approval before implementing

