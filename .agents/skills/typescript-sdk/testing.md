# TypeScript SDK Testing

## Test Runner
Vitest - run with `npm test` in `sdks/typescript`

## Key Patterns

### Always Flush Before Assertions
```typescript
// Data only persists after flush
const client = new Opik();
client.trace({ name: "test" });
await client.flush();  // Required!

// Then assert
expect(mockFetch).toHaveBeenCalledWith(/* ... */);
```

### Mock Network and Timers
```typescript
import { vi } from 'vitest';

// Mock fetch for deterministic tests
vi.stubGlobal('fetch', vi.fn().mockResolvedValue(/* ... */));

// Use fake timers for batching tests
vi.useFakeTimers();
// ... do work ...
vi.advanceTimersByTime(1000);  // Trigger batch flush
```

### Control Batching in Tests
```typescript
// Option 1: Small delays
const client = new Opik({ batchDelay: 10 });

// Option 2: Advance fake timers
vi.advanceTimersByTime(1000);

// Option 3: Explicit flush
await client.flush();
```

## Integration Testing

```typescript
// Mock provider clients
const mockOpenAI = {
  chat: {
    completions: {
      create: vi.fn().mockResolvedValue({ /* response */ }),
    },
  },
};

// Assert spans recorded
expect(mockFetch).toHaveBeenCalledWith(
  expect.stringContaining('/spans'),
  expect.objectContaining({ method: 'POST' })
);
```

## Best Practices
- Test public API only, avoid `rest_api` internals
- Mock network to keep tests deterministic
- Assert no `ERROR` level logs unintentionally
- Always `flush()` before checking persisted data
