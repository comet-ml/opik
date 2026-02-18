---
name: typescript-sdk
description: TypeScript SDK patterns for Opik. Use when working in sdks/typescript.
---

# TypeScript SDK

## Architecture
- Layered, non-blocking by default
- Data buffered and flushed async to backend
- Node >= 18, ESM + CJS builds

## Layer Flow
```
Public API → OpikClient → Domain (Trace/Span) → BatchQueues → REST Client → Backend
```

## Critical Gotchas

### Flush Before Exit
```typescript
// ✅ REQUIRED - especially in CLI/tests
await client.flush();
// or globally:
await flushAll();
```

### Domain Objects Don't Do HTTP
```typescript
// ✅ GOOD - domain objects enqueue, not HTTP
trace.update({ metadata: { key: 'value' } }); // Enqueues update
trace.end();  // Enqueues update

// ❌ BAD - don't call REST directly from domain
```

### Never Leak rest_api
```typescript
// ✅ GOOD - export from public API
export { Opik, track, flushAll } from 'opik';

// ❌ BAD - don't expose generated clients
import { TracesApi } from 'opik/rest_api';  // Internal!
```

## Batching Semantics
- Updates wait for pending creates
- Deletes wait for creates and updates
- `flush()` flushes all queues in order
- Debounce window configurable via `OpikConfig`

## Error Handling
- HTTP failures: `OpikApiError`, `OpikApiTimeoutError`
- 404s translate to domain errors: `DatasetNotFoundError`, `ExperimentNotFoundError`
- Never swallow errors, include context in logs

## Integration Guidelines
- Integrations wrap public API only
- Keep adapters thin, non-blocking
- Provide `flush()` escape hatch if needed

## Reference Files
- [testing.md](testing.md) - Vitest patterns, mocking, flush timing
