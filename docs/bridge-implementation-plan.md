# Bridge Command Queue — Opik Backend Implementation Plan

## Overview

Add bridge command relay to the Opik backend. The backend acts as a dumb relay between OllieAssist (submits commands, awaits results) and the local daemon (polls commands, reports results). Bridge commands are short-lived file operations (read_file, write_file, etc.) with separate lifecycle from existing runner jobs.

## Files to Create

### New Model/DTO Classes (`com.comet.opik.api.runner`)

**`BridgeCommandType.java`** — Enum of allowed command types
- Values: `READ_FILE`, `WRITE_FILE`, `EDIT_FILE`, `LIST_FILES`, `SEARCH_FILES`
- Serialized as snake_case strings (matching existing `LocalRunnerJobStatus` pattern)
- Validated on submit — backend rejects unknown types at the gate

**`BridgeCommand.java`** — Record for the full command state (returned by await endpoint)
- Fields: `id`, `runnerId`, `type`, `args` (JsonNode), `status`, `result` (JsonNode), `error`, `timeoutSeconds`, `submittedAt`, `pickedUpAt`, `completedAt`, `durationMs`
- Uses `@JsonNaming(SnakeCaseStrategy.class)` and `@Builder` like `LocalRunnerJob`

**`BridgeCommandStatus.java`** — Enum: `PENDING`, `PICKED_UP`, `COMPLETED`, `FAILED`, `TIMED_OUT`
- Follows same `@JsonValue` pattern as `LocalRunnerJobStatus`

**`BridgeCommandSubmitRequest.java`** — Request body for submit endpoint
- Fields: `type` (BridgeCommandType, required), `args` (JsonNode, required), `timeoutSeconds` (Integer, optional)

**`BridgeCommandSubmitResponse.java`** — Response for submit endpoint
- Fields: `commandId` (UUID)

**`BridgeCommandBatchResponse.java`** — Response for poll-next endpoint
- Fields: `commands` (List of BridgeCommandItem)
- `BridgeCommandItem`: `commandId`, `type`, `args`, `timeoutSeconds`, `submittedAt`

**`BridgeCommandResultRequest.java`** — Request body for report-result endpoint
- Fields: `status` (COMPLETED or FAILED), `result` (JsonNode, optional), `error` (JsonNode, optional), `durationMs` (Long, optional)

**`BridgeCommandNextRequest.java`** — Request body for poll-next endpoint
- Fields: `maxCommands` (Integer, optional, default 10, max 20)

**`BridgeCommandError.java`** — Nested error structure
- Fields: `code` (String), `message` (String)

## Files to Modify

### `LocalRunnerConfig.java`

Add bridge-specific configuration fields:
- `bridgeMaxPendingPerRunner` (int, default 20) — max pending commands before 429
- `bridgeMaxCommandsPerMinute` (int, default 60) — sliding window rate limit
- `bridgeMaxWriteCommandsPerMinute` (int, default 10) — write-specific rate limit
- `bridgePollTimeout` (Duration, default 30s) — long-poll timeout for daemon next endpoint
- `bridgeDefaultCommandTimeout` (Duration, default 30s) — default timeout per command
- `bridgeMaxCommandTimeout` (Duration, default 120s) — max allowed timeout
- `bridgeCompletedCommandTtl` (Duration, default 1h) — TTL for completed commands
- `bridgeAsyncTimeoutBuffer` (Duration, default 5s) — buffer added to async response timeout

### `LocalRunnerService.java` (interface)

Add new methods:
- `submitBridgeCommand(runnerId, workspaceId, userName, request)` → UUID
- `nextBridgeCommands(runnerId, workspaceId, userName, maxCommands)` → Mono (long-poll, follows existing `nextJob` pattern)
- `reportBridgeCommandResult(runnerId, workspaceId, userName, commandId, request)` → void
- `getBridgeCommand(runnerId, workspaceId, userName, commandId)` → BridgeCommand
- `awaitBridgeCommand(runnerId, workspaceId, userName, commandId, timeoutSeconds)` → Mono (long-poll for result)
- `reapStaleBridgeCommands(runnerId)` → void (called from existing reaper)
- `failOrphanedBridgeCommands(runnerId)` → void (called on runner death)

### `LocalRunnerServiceImpl.java` (implementation)

**Redis key helpers** — new private methods following the existing `xxxKey()` naming pattern:
- `bridgeCommandKey(commandId)` → `opik:runners:bridge:{commandId}`
- `bridgeCommandDoneKey(commandId)` → `opik:runners:bridge:{commandId}:done`
- `bridgePendingKey(runnerId)` → `opik:runners:bridge:{runnerId}:pending`
- `bridgeActiveKey(runnerId)` → `opik:runners:bridge:{runnerId}:active`
- `bridgeRateKey(runnerId, minute)` → `opik:runners:bridge:{runnerId}:rate:{minute}`
- `bridgeWriteRateKey(runnerId, minute)` → `opik:runners:bridge:{runnerId}:write_rate:{minute}`

**`submitBridgeCommand`**:
1. Validate runner ownership (workspace + user scoping, same as existing job creation)
2. Verify runner is alive (heartbeat bucket exists)
3. Verify runner has bridge capability (check `capabilities` field on runner hash)
4. Check pending list length < `bridgeMaxPendingPerRunner`, else 429
5. Check sliding window rate counters, else 429
6. Clamp `timeoutSeconds` to [1, maxCommandTimeout]
7. Create command hash in Redis with all fields, status=PENDING, submittedAt=now
8. Set TTL on command hash: `timeoutSeconds + 30s`
9. Push commandId to pending list (RPUSH for FIFO)
10. Return commandId

**`nextBridgeCommands`** (long-poll):
- Follow the existing `nextJob` pattern using `RBlockingDeque.moveAsync()` for the first command
- After first command arrives (or timeout), drain remaining with non-blocking moves up to `maxCommands`
- Move all dequeued commands from pending to active set
- Update each command: status=PICKED_UP, pickedUpAt=now
- Return batch of command items
- Use Mono/reactive like existing `nextJob`

**`reportBridgeCommandResult`**:
1. Validate command exists and belongs to this runner
2. Check command is not already terminal (409 on duplicate)
3. Update command hash: status, result/error, completedAt=now, durationMs
4. Remove from active set
5. Write sentinel to done blocking queue (wakes up awaiting OllieAssist connection)
6. Update TTL on command hash to `completedCommandTtl`

**`awaitBridgeCommand`** (long-poll for OllieAssist):
1. Read command hash — if already terminal, return immediately
2. If not terminal: `RBlockingQueue.poll(timeout)` on the done key
3. On wake (or timeout expiry), re-read command hash and return full state
- Follow the existing `RBlockingDeque` async pattern, returning a Mono

**`reapStaleBridgeCommands`**:
- Scan active set for commands past `timeoutSeconds + 10s`
- Mark timed_out, write done sentinel, remove from active

**`failOrphanedBridgeCommands`**:
- Drain pending list, drain active set
- Mark all as timed_out, write done sentinels

**Heartbeat extension**:
- Accept optional `capabilities` list in heartbeat (requires adding a request body)
- Store as comma-separated string in runner hash (new field `capabilities`)
- Default to `"jobs"` if absent (backward compatible)
- Expose in `getRunner()` and `listRunners()` responses

### `LocalRunnersResource.java`

Add 4 new endpoints under the existing class:

**`POST /{runnerId}/bridge/commands`** — submitBridgeCommand
- Follows same auth pattern as `createJob` (workspace + user from RequestContext)
- Validates request body with Jakarta annotations
- Returns 201 with `BridgeCommandSubmitResponse`
- Location header pointing to the await endpoint

**`POST /{runnerId}/bridge/next`** — nextBridgeCommands
- Uses `@Suspended AsyncResponse` pattern matching existing `nextJob`
- Async timeout = bridgePollTimeout + bridgeAsyncTimeoutBuffer
- Returns 200 with `BridgeCommandBatchResponse`

**`POST /{runnerId}/bridge/commands/{commandId}/result`** — reportBridgeCommandResult
- Returns 200 on success
- 404 if command not found / not owned by runner
- 409 if already completed

**`GET /{runnerId}/bridge/commands/{commandId}`** — getBridgeCommand / awaitBridgeCommand
- `@QueryParam("wait")` boolean, `@QueryParam("timeout")` int
- If `wait=false` or absent: synchronous read, returns current state
- If `wait=true`: uses `@Suspended AsyncResponse` for long-poll
- Returns 200 with full `BridgeCommand`

**Heartbeat modification** — change `heartbeat()` to accept optional request body:
- Add `LocalRunnerHeartbeatRequest` DTO with optional `capabilities` field
- Keep backward compatible: null body or missing field → defaults to `["jobs"]`

### `LocalRunner.java` (model)

Add `capabilities` field (List<String>) to the record. Included in list/get responses.

### `LocalRunnerReaperJob.java` / reaper integration

Extend the existing reaper flow:
- In `failOrphanedJobs()` (called on dead runner purge), also call `failOrphanedBridgeCommands()`
- In `reapStuckJobs()` (periodic), also call `reapStaleBridgeCommands()`
- In runner data purge, also delete bridge keys (pending list, active set, rate counters)

## Redis Key Layout

```
opik:runners:bridge:{commandId}              # Hash — full command state
opik:runners:bridge:{commandId}:done         # BlockingQueue(1) — sentinel for await
opik:runners:bridge:{runnerId}:pending       # BlockingDeque — FIFO pending command IDs
opik:runners:bridge:{runnerId}:active        # Set — currently executing command IDs
opik:runners:bridge:{runnerId}:rate:{minute} # Atomic counter + TTL — commands/minute
opik:runners:bridge:{runnerId}:write_rate:{minute} # Atomic counter + TTL — writes/minute
```

TTLs:
- Command hash (pending): `timeoutSeconds + 30s`
- Command hash (completed): 1 hour
- Done queue: 1 hour
- Rate counters: 120 seconds (covers the sliding window)

## Testing Plan

### Unit Tests — `LocalRunnerServiceImplTest.java` (extend existing file)

Follows the existing test patterns exactly:
- Same `@TestInstance(PER_CLASS)` lifecycle
- Same real Redis container (not mocked)
- Same `@BeforeEach` flush + counter reset
- Same `pairAndConnect()` helper for setup
- Same direct Redis key inspection for assertions
- Same `@Nested` class organization

New nested test classes to add:

**`@Nested class BridgeSubmitCommand`**
- Submit creates command hash in Redis with correct fields and PENDING status
- Submit pushes commandId to runner's pending list
- Submit sets TTL on command hash
- Submit to unknown runner throws 404
- Submit to disconnected runner (expired heartbeat) throws 404
- Submit to runner without bridge capability throws 409
- Submit when pending list is full throws 429
- Submit when rate limit exceeded throws 429
- Submit when write rate limit exceeded (for write_file/edit_file types) throws 429
- Submit clamps timeout to configured max
- Submit with unknown command type throws 400

**`@Nested class BridgeNextCommands`**
- Single pending command: returns batch of one, moves to active, sets PICKED_UP
- Multiple pending: returns up to maxCommands, all moved to active
- No pending: blocks then returns empty (use short poll timeout in test config)
- Respects maxCommands limit (submit 5, request max 3 → get 3)
- Evicted runner throws 410
- Wrong workspace throws 404

**`@Nested class BridgeReportResult`**
- Completed: updates hash with result, removes from active, writes done sentinel
- Failed: updates hash with error
- Duplicate report throws 409
- Command not owned by runner throws 404
- Verify done queue sentinel is written (inspect Redis key directly)

**`@Nested class BridgeAwaitCommand`**
- Already completed: returns immediately with full state
- Pending then completed: submit, report result in another thread, verify await unblocks
- Timeout: await expires, returns non-terminal state
- No wait flag: returns current state immediately

**`@Nested class BridgeHeartbeatCapabilities`**
- Heartbeat with capabilities stores on runner hash
- Heartbeat without capabilities defaults to `["jobs"]`
- getRunner includes capabilities in response

**`@Nested class BridgeReaper`**
- Dead runner: failOrphanedBridgeCommands marks pending + active as timed_out
- Dead runner: done sentinels are written (so any waiting await unblocks)
- Active command past deadline: reapStaleBridgeCommands marks timed_out
- Reaper cleans up bridge keys on runner purge

**Test helpers to add:**
- `pairAndConnectWithBridge(workspaceId, userName, runnerName)` — pairs, connects, and sends heartbeat with `capabilities: ["jobs", "bridge"]`
- `submitBridgeCommand(workspaceId, userName, type, args)` — convenience wrapper

### Integration Tests — `LocalRunnersResourceTest.java` (extend existing file)

Follows the existing test patterns exactly:
- Same multi-container setup (Redis, MySQL, ClickHouse, WireMock)
- Same `LocalRunnersResourceClient` for HTTP calls
- Same `connectRunnerWithPairing()` helpers
- Same `createIsolatedWorkspace()` for test isolation
- Same two-method pattern per endpoint: success method + raw response method

New client methods to add to `LocalRunnersResourceClient.java`:
- `submitBridgeCommand(runnerId, request, apiKey, workspace)` → BridgeCommandSubmitResponse
- `callSubmitBridgeCommand(...)` → Response
- `nextBridgeCommands(runnerId, request, apiKey, workspace)` → BridgeCommandBatchResponse
- `callNextBridgeCommands(...)` → Response
- `reportBridgeResult(runnerId, commandId, request, apiKey, workspace)` → void
- `callReportBridgeResult(...)` → Response
- `getBridgeCommand(runnerId, commandId, wait, timeout, apiKey, workspace)` → BridgeCommand
- `callGetBridgeCommand(...)` → Response
- `heartbeatWithCapabilities(runnerId, capabilities, apiKey, workspace)` → LocalRunnerHeartbeatResponse

New nested test classes to add:

**`@Nested class BridgeHappyPath`**
- Full lifecycle: connect runner with bridge capability → submit command → poll next → report result → await returns completed
- Batch flow: submit 5 commands → poll returns all 5 → report individually → all awaits resolve
- Verify no interference with existing job endpoints (submit bridge command + create job concurrently, both work independently)

**`@Nested class BridgeSubmit`**
- Runner without bridge capability → 409
- Runner not connected → 404
- Invalid command type → 400
- Queue full → 429 (use low maxPending config like existing maxPendingJobsPerRunner=3 pattern)
- Wrong workspace → 404

**`@Nested class BridgeAwait`**
- Long-poll unblocks on result: submit, await in background thread, report → await returns
- wait=false returns current non-terminal state
- Command not found → 404

**`@Nested class BridgeRateLimit`**
- Exceed commands per minute → 429
- Exceed write commands per minute → 429

**`@Nested class BridgeHeartbeat`**
- Capabilities stored and returned in getRunner response
- Old-style heartbeat (no body) still works, defaults to jobs-only

### Reaper Integration Tests — `LocalRunnerReaperIntegrationTest.java` (extend existing file)

New nested class:

**`@Nested class ReapBridgeCommands`**
- Dead runner with pending bridge commands → all marked timed_out
- Dead runner with active bridge commands → all marked timed_out, done sentinels written
- Stuck active bridge command past deadline → marked timed_out
- Runner purge deletes bridge keys (pending list, active set)

## Ordering

1. DTOs and enums (no dependencies, pure data classes)
2. Config additions (needed by service)
3. Service layer (Redis operations)
4. Resource endpoints (depends on service + DTOs)
5. Heartbeat extension (modifies existing endpoint, touches runner model)
6. Reaper extension (depends on service bridge methods)
7. Unit tests (as each service method is built)
8. Integration test client methods
9. Integration tests

## Non-Goals

- No changes to existing job endpoints or behavior
- No frontend changes
- No daemon/Python changes (separate repo)
- No OllieAssist changes (separate repo)
- No command-type-specific arg validation (backend is a dumb relay — it validates the type enum exists but not the args schema)
