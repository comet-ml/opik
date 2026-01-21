# Opik TypeScript SDK Testing Guide

## Table of Contents

- [Overview](#overview)
- [Test Infrastructure](#test-infrastructure)
- [Test Categories](#test-categories)
- [Testing Patterns](#testing-patterns)
- [Mocking Strategies](#mocking-strategies)
- [Running Tests](#running-tests)
- [Writing New Tests](#writing-new-tests)

## Overview

The TypeScript SDK uses **Vitest** as its test framework with **MSW (Mock Service Worker)** for API mocking. Tests are organized by category and follow consistent patterns for reliability and maintainability.

### Technology Stack

| Tool | Purpose |
|------|---------|
| **Vitest** | Test runner and assertions |
| **MSW** | HTTP request interception and mocking |
| **vite-tsconfig-paths** | Path alias resolution in tests |

### Test Directory Structure

```
tests/
├── setup.ts                    # Global test setup
├── mockUtils.ts                # MSW helper utilities
├── batch.test.ts               # Batch queue tests
├── config.test.ts              # Configuration tests
├── track.test.ts               # Decorator tests
├── scores.test.ts              # Feedback score tests
├── lazy-loading.test.ts        # Module loading tests
├── dataset/
│   ├── client-datasets.test.ts
│   ├── dataset-entity.test.ts
│   └── dataset-deduplication-items.test.ts
├── evaluation/
│   ├── evaluate.test.ts
│   ├── evaluatePrompt.test.ts
│   └── metrics/
│       ├── llmJudges/
│       ├── formatMessages.test.ts
├── experiment/
│   ├── client-experiment.test.ts
│   └── experiment-entity.test.ts
├── unit/
│   ├── client/
│   ├── evaluation/
│   ├── metrics/
│   ├── prompt/
│   ├── query/
│   └── utils/
├── integration/
│   ├── api/
│   ├── chat-prompts.test.ts
│   └── evaluation/
└── utils/
    └── index.ts
```

## Test Infrastructure

### Global Setup

**Location**: `tests/setup.ts`

```typescript
import { beforeAll, afterAll, afterEach } from "vitest";
import { server } from "./mockUtils";

beforeAll(() => {
  server.listen({ onUnhandledRequest: "error" });
});

afterEach(() => {
  server.resetHandlers();
});

afterAll(() => {
  server.close();
});
```

### MSW Server Configuration

**Location**: `tests/mockUtils.ts`

```typescript
import { setupServer } from "msw/node";
import { http, HttpResponse } from "msw";

export const server = setupServer();

// Helper to create mock handlers
export const mockEndpoint = (
  method: "get" | "post" | "put" | "delete",
  path: string,
  response: unknown,
  status = 200
) => {
  return http[method](path, () => {
    return HttpResponse.json(response, { status });
  });
};
```

### Vitest Configuration

**Location**: `vitest.config.ts`

```typescript
import { defineConfig } from "vitest/config";
import tsconfigPaths from "vite-tsconfig-paths";

export default defineConfig({
  plugins: [tsconfigPaths()],
  test: {
    globals: true,
    environment: "node",
    setupFiles: ["./tests/setup.ts"],
    include: ["tests/**/*.test.ts"],
    exclude: ["tests/integration/**/*.test.ts"],
  },
});
```

## Test Categories

### Unit Tests

Pure logic tests without external dependencies.

**Location**: `tests/unit/`

**Examples**:
- Query language parsing
- Prompt formatting
- Metric calculations
- Utility functions

```typescript
// tests/unit/query/oql.test.ts
describe("OpikQueryLanguage", () => {
  it("parses simple equality filter", () => {
    const oql = new OpikQueryLanguage('name = "test"');
    expect(oql.getFilterExpressions()).toEqual([
      { field: "name", operator: "=", value: "test", type: "string" }
    ]);
  });
});
```

### Component Tests

Tests for SDK components with mocked API responses.

**Location**: `tests/` (root level)

**Examples**:
- Batch queue behavior
- Configuration loading
- Track decorator
- Dataset operations

```typescript
// tests/batch.test.ts
describe("BatchQueue", () => {
  it("batches multiple creates", async () => {
    const createHandler = vi.fn();
    server.use(
      http.post("*/traces/batch", async ({ request }) => {
        createHandler(await request.json());
        return HttpResponse.json({});
      })
    );

    const client = new Opik({ holdUntilFlush: true });
    client.trace({ name: "trace1" });
    client.trace({ name: "trace2" });
    
    await client.flush();
    
    expect(createHandler).toHaveBeenCalledTimes(1);
    expect(createHandler).toHaveBeenCalledWith(
      expect.objectContaining({
        traces: expect.arrayContaining([
          expect.objectContaining({ name: "trace1" }),
          expect.objectContaining({ name: "trace2" }),
        ])
      })
    );
  });
});
```

### Integration Tests

Tests against real or simulated backend.

**Location**: `tests/integration/`

**Note**: Excluded from default test run; requires `npm run test:integration`

```typescript
// tests/integration/api/traces.test.ts
describe("Traces API Integration", () => {
  it("creates and retrieves trace", async () => {
    const client = new Opik();
    const trace = client.trace({ name: "integration-test" });
    await client.flush();
    
    const traces = await client.searchTraces({
      filterString: `name = "integration-test"`,
      waitForAtLeast: 1,
      waitForTimeout: 30,
    });
    
    expect(traces).toHaveLength(1);
    expect(traces[0].name).toBe("integration-test");
  });
});
```

### Metric Tests

Tests for evaluation metrics.

**Location**: `tests/evaluation/metrics/` and `tests/unit/metrics/`

```typescript
// tests/evaluation/metrics/contains.test.ts
describe("Contains metric", () => {
  it("returns 1.0 when output contains expected", () => {
    const metric = new Contains();
    const result = metric.score({
      output: "The answer is 42",
      expected: "42",
    });
    expect(result).toEqual({ name: "contains_metric", value: 1.0 });
  });
});
```

## Testing Patterns

### Pattern 1: Mocked API Client

For testing SDK behavior without real API calls:

```typescript
import { Opik } from "opik";
import { server } from "./mockUtils";
import { http, HttpResponse } from "msw";

describe("Client operations", () => {
  beforeEach(() => {
    server.use(
      http.post("*/traces/batch", () => HttpResponse.json({})),
      http.post("*/spans/batch", () => HttpResponse.json({})),
    );
  });

  it("creates trace with correct data", async () => {
    const capturedRequest = vi.fn();
    server.use(
      http.post("*/traces/batch", async ({ request }) => {
        capturedRequest(await request.json());
        return HttpResponse.json({});
      })
    );

    const client = new Opik({ holdUntilFlush: true });
    client.trace({ name: "test", input: { query: "hello" } });
    await client.flush();

    expect(capturedRequest).toHaveBeenCalledWith(
      expect.objectContaining({
        traces: [expect.objectContaining({
          name: "test",
          input: { query: "hello" },
        })]
      })
    );
  });
});
```

### Pattern 2: holdUntilFlush for Deterministic Tests

Use `holdUntilFlush: true` to prevent automatic flushing:

```typescript
it("batches operations correctly", async () => {
  const client = new Opik({ holdUntilFlush: true });
  
  // Operations are queued but not sent
  const trace = client.trace({ name: "test" });
  trace.span({ name: "span1" });
  trace.span({ name: "span2" });
  
  // Nothing sent yet
  expect(mockHandler).not.toHaveBeenCalled();
  
  // Explicit flush sends everything
  await client.flush();
  
  expect(mockHandler).toHaveBeenCalled();
});
```

### Pattern 3: Testing Decorators

```typescript
import { track, getTrackContext, _resetTrackOpikClientCache } from "opik";

describe("track decorator", () => {
  beforeEach(() => {
    _resetTrackOpikClientCache();
  });

  it("creates trace and span for root call", async () => {
    const trackedFn = track(async (input: string) => {
      return `processed: ${input}`;
    });

    const result = await trackedFn("test");
    
    expect(result).toBe("processed: test");
    // Verify spans were created via mock handlers
  });

  it("nests spans for nested calls", async () => {
    const inner = track(async () => "inner result");
    const outer = track(async () => {
      return await inner();
    });

    await outer();
    // Verify parent-child relationship in captured requests
  });
});
```

### Pattern 4: Testing Metrics

```typescript
import { ExactMatch } from "opik/evaluation/metrics";

describe("ExactMatch metric", () => {
  const metric = new ExactMatch();

  it.each([
    { output: "hello", expected: "hello", expectedValue: 1.0 },
    { output: "hello", expected: "world", expectedValue: 0.0 },
    { output: "HELLO", expected: "hello", expectedValue: 0.0 },
  ])("returns $expectedValue for output=$output, expected=$expected", 
    ({ output, expected, expectedValue }) => {
      const result = metric.score({ output, expected });
      expect(result.value).toBe(expectedValue);
    }
  );
});
```

### Pattern 5: Testing Async Operations

```typescript
describe("async operations", () => {
  it("handles concurrent traces", async () => {
    const client = new Opik({ holdUntilFlush: true });
    
    // Create multiple traces concurrently
    const traces = await Promise.all([
      Promise.resolve(client.trace({ name: "trace1" })),
      Promise.resolve(client.trace({ name: "trace2" })),
      Promise.resolve(client.trace({ name: "trace3" })),
    ]);

    await client.flush();
    
    // Verify all traces were batched
    expect(capturedTraces).toHaveLength(3);
  });
});
```

## Mocking Strategies

### Strategy 1: MSW Request Handlers

```typescript
// Mock successful response
server.use(
  http.get("*/datasets/:name", ({ params }) => {
    return HttpResponse.json({
      id: "dataset-123",
      name: params.name,
      description: "Test dataset",
    });
  })
);

// Mock error response
server.use(
  http.get("*/datasets/:name", () => {
    return HttpResponse.json(
      { error: "Not found" },
      { status: 404 }
    );
  })
);

// Mock with delay
server.use(
  http.post("*/traces/batch", async () => {
    await new Promise(r => setTimeout(r, 100));
    return HttpResponse.json({});
  })
);
```

### Strategy 2: Capturing Requests

```typescript
const capturedRequests: unknown[] = [];

server.use(
  http.post("*/spans/batch", async ({ request }) => {
    capturedRequests.push(await request.json());
    return HttpResponse.json({});
  })
);

// After test operations
expect(capturedRequests).toHaveLength(1);
expect(capturedRequests[0]).toMatchObject({
  spans: expect.any(Array),
});
```

### Strategy 3: Streaming Response Mocks

```typescript
server.use(
  http.get("*/datasets/:id/items/stream", () => {
    const items = [
      { id: "1", data: { input: "test1" } },
      { id: "2", data: { input: "test2" } },
    ];
    
    const ndjson = items.map(i => JSON.stringify(i)).join("\n");
    
    return new HttpResponse(ndjson, {
      headers: { "Content-Type": "application/x-ndjson" },
    });
  })
);
```

## Running Tests

### Commands

```bash
# Run unit tests (excludes integration)
npm test

# Run with watch mode
npm test -- --watch

# Run specific test file
npm test -- tests/batch.test.ts

# Run tests matching pattern
npm test -- --grep "BatchQueue"

# Run integration tests (requires backend)
npm run test:integration

# Run CI tests (excludes some integration tests)
npm run test:ci

# Run with coverage
npm test -- --coverage
```

### Environment Variables for Tests

```bash
# For integration tests
OPIK_API_KEY=your-test-key
OPIK_URL_OVERRIDE=http://localhost:5173/api
OPIK_PROJECT_NAME=test-project
```

## Writing New Tests

### Checklist

1. **Choose test category**: Unit, component, or integration?
2. **Set up mocks**: Use MSW for API mocking
3. **Use `holdUntilFlush`**: For deterministic batch testing
4. **Clean up**: Reset handlers and caches in `beforeEach`
5. **Assert captured data**: Verify request payloads, not just responses

### Template: Component Test

```typescript
import { describe, it, expect, beforeEach, vi } from "vitest";
import { Opik } from "opik";
import { server } from "../mockUtils";
import { http, HttpResponse } from "msw";

describe("FeatureName", () => {
  let client: Opik;
  let capturedRequests: unknown[];

  beforeEach(() => {
    capturedRequests = [];
    
    server.use(
      http.post("*/relevant/endpoint", async ({ request }) => {
        capturedRequests.push(await request.json());
        return HttpResponse.json({ success: true });
      })
    );

    client = new Opik({ holdUntilFlush: true });
  });

  it("should do something specific", async () => {
    // Arrange
    const input = { /* test data */ };

    // Act
    await client.someMethod(input);
    await client.flush();

    // Assert
    expect(capturedRequests).toHaveLength(1);
    expect(capturedRequests[0]).toMatchObject({
      /* expected structure */
    });
  });

  it("should handle errors gracefully", async () => {
    server.use(
      http.post("*/relevant/endpoint", () => {
        return HttpResponse.json({ error: "Failed" }, { status: 500 });
      })
    );

    // Act & Assert
    await expect(client.someMethod({})).rejects.toThrow();
  });
});
```

### Template: Unit Test

```typescript
import { describe, it, expect } from "vitest";
import { SomeUtility } from "@/utils/someUtility";

describe("SomeUtility", () => {
  describe("methodName", () => {
    it("handles normal input", () => {
      const result = SomeUtility.methodName("input");
      expect(result).toBe("expected");
    });

    it("handles edge case", () => {
      const result = SomeUtility.methodName("");
      expect(result).toBe("");
    });

    it("throws on invalid input", () => {
      expect(() => SomeUtility.methodName(null)).toThrow();
    });
  });
});
```

### Template: Metric Test

```typescript
import { describe, it, expect } from "vitest";
import { MyMetric } from "@/evaluation/metrics";

describe("MyMetric", () => {
  const metric = new MyMetric();

  it("has correct name", () => {
    expect(metric.name).toBe("my_metric");
  });

  describe("score", () => {
    it("returns expected value for valid input", () => {
      const result = metric.score({
        output: "test output",
        expected: "expected value",
      });

      expect(result).toEqual({
        name: "my_metric",
        value: expect.any(Number),
      });
    });

    it("validates input schema", () => {
      expect(() => metric.score({ invalid: "data" })).toThrow();
    });
  });
});
```
