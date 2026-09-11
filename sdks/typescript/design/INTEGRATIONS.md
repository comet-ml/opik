# Opik TypeScript SDK Integrations Guide

## Table of Contents

- [Overview](#overview)
- [Integration Architecture](#integration-architecture)
- [Integration Patterns](#integration-patterns)
- [Existing Integrations](#existing-integrations)
- [Creating New Integrations](#creating-new-integrations)
- [Streaming Support](#streaming-support)

## Overview

The TypeScript SDK provides integrations with popular LLM frameworks through **separate npm packages**. Each integration package wraps or instruments a framework to automatically capture traces and spans.

### Design Philosophy

1. **Separate packages**: Each integration is its own npm package (`opik-openai`, `opik-langchain`, etc.)
2. **Non-invasive**: Minimal changes to user code
3. **Automatic capture**: Input, output, usage, and errors captured automatically
4. **Streaming support**: Full support for streamed responses

### Integration Packages

| Package | Framework | Pattern |
|---------|-----------|---------|
| `opik-openai` | OpenAI SDK | Proxy wrapper |
| `opik-langchain` | LangChain.js | Callback handler |
| `opik-vercel` | Vercel AI SDK | OpenTelemetry exporter |
| `opik-gemini` | Google Gemini | Proxy wrapper |

## Integration Architecture

### Package Structure

Each integration follows this structure:

```
src/opik/integrations/opik-{name}/
├── package.json          # Separate npm package
├── tsconfig.json
├── tsup.config.ts
├── vitest.config.ts
├── README.md
├── src/
│   ├── index.ts          # Main exports
│   ├── decorators.ts     # Wrapping logic (if applicable)
│   ├── parsers.ts        # Response parsing
│   ├── types.ts          # TypeScript types
│   ├── utils.ts          # Helper functions
│   └── singleton.ts      # Shared client instance
└── tests/
    └── *.test.ts
```

### Dependency Flow

```
┌─────────────────────────────────────┐
│          User Application            │
├─────────────────────────────────────┤
│  Integration Package (opik-openai)   │
│  ┌─────────────────────────────────┐ │
│  │    Wrapper / Handler / Exporter │ │
│  └──────────────┬──────────────────┘ │
├─────────────────┼───────────────────┤
│  Core SDK (opik)│                    │
│  ┌──────────────┴──────────────────┐ │
│  │         OpikClient              │ │
│  │  ┌───────┐ ┌───────┐ ┌───────┐  │ │
│  │  │Traces │ │ Spans │ │Scores │  │ │
│  │  └───────┘ └───────┘ └───────┘  │ │
│  └─────────────────────────────────┘ │
└─────────────────────────────────────┘
```

## Integration Patterns

### Pattern 1: Proxy Wrapper

**Used by**: `opik-openai`, `opik-gemini`

Wraps the SDK client using JavaScript Proxy to intercept method calls.

**Location**: `opik-openai/src/trackOpenAI.ts`

```typescript
export const trackOpenAI = <SDKType extends object>(
  sdk: SDKType,
  opikConfig?: TrackOpikConfig
): SDKType & OpikExtension => {
  return new Proxy(sdk, {
    get(wrappedSdk, propKey, proxy) {
      const originalProperty = wrappedSdk[propKey as keyof SDKType];

      // Add flush method
      if (propKey === "flush") {
        return config.client.flush.bind(config.client);
      }

      // Wrap functions with tracing
      if (typeof originalProperty === "function") {
        return withTracing(originalProperty.bind(wrappedSdk), config);
      }

      // Recursively wrap nested objects
      if (isNestedObject(originalProperty)) {
        return trackOpenAI(originalProperty, config);
      }

      return Reflect.get(wrappedSdk, propKey, proxy);
    },
  });
};
```

**Usage**:

```typescript
import OpenAI from "openai";
import { trackOpenAI } from "opik-openai";

const openai = trackOpenAI(new OpenAI());

// All calls are automatically traced
const response = await openai.chat.completions.create({
  model: "gpt-4",
  messages: [{ role: "user", content: "Hello!" }],
});

await openai.flush();
```

**Advantages**:
- Transparent to user code
- Works with any SDK method
- Preserves TypeScript types

**Tradeoffs**:
- Requires understanding SDK structure
- May need updates when SDK changes

### Pattern 2: Callback Handler

**Used by**: `opik-langchain`

Implements framework-specific callback interface.

**Location**: `opik-langchain/src/OpikCallbackHandler.ts`

```typescript
export class OpikCallbackHandler
  extends BaseCallbackHandler
  implements BaseCallbackHandlerInput
{
  name = "OpikCallbackHandler";
  private client: Opik;
  private tracerMap: Map<string, Trace | Span> = new Map();

  async handleLLMStart(
    llm: Serialized,
    prompts: string[],
    runId: string,
    parentRunId?: string,
    // ...
  ): Promise<void> {
    this.startTracing({
      runId,
      parentRunId,
      name: llm.id.at(-1)?.toString() ?? "LLM",
      type: OpikSpanType.Llm,
      input: { prompts },
    });
  }

  async handleLLMEnd(
    output: LLMResult,
    runId: string,
    // ...
  ): Promise<void> {
    this.endTracing({
      runId,
      output: outputFromGenerations(output.generations),
      usage: extractTokenUsage(output),
    });
  }

  // Similar handlers for chains, tools, retrievers, agents...
}
```

**Usage**:

```typescript
import { ChatOpenAI } from "@langchain/openai";
import { OpikCallbackHandler } from "opik-langchain";

const handler = new OpikCallbackHandler({
  projectName: "my-project",
  tags: ["production"],
});

const model = new ChatOpenAI({ callbacks: [handler] });

const response = await model.invoke("Hello!");

await handler.flushAsync();
```

**Advantages**:
- Framework-native pattern
- Handles complex chains automatically
- Built-in parent-child relationships

**Tradeoffs**:
- Framework-specific implementation
- Must implement all callback methods

### Pattern 3: OpenTelemetry Exporter

**Used by**: `opik-vercel`

Exports OpenTelemetry spans to Opik.

**Location**: `opik-vercel/src/exporter.ts`

```typescript
export class OpikExporter implements SpanExporter {
  private readonly traces = new Map<string, Trace>();
  private readonly spans = new Map<string, Span>();
  private readonly client: Opik;

  export: ExportFunction = async (allOtelSpans, resultCallback) => {
    const aiSDKOtelSpans = allOtelSpans.filter(
      (span) => span.instrumentationScope.name === "ai"
    );

    const spanGroups = groupAndSortOtelSpans(aiSDKOtelSpans);

    Object.entries(spanGroups).forEach(([otelTraceId, otelSpans]) => {
      const [rootOtelSpan, ...otherOtelSpans] = otelSpans;

      const trace = this.client.trace({
        name: rootOtelSpan.name,
        input: this.getSpanInput(rootOtelSpan),
        output: this.getSpanOutput(rootOtelSpan),
        // ...
      });

      this.traces.set(otelTraceId, trace);

      otherOtelSpans.forEach((otelSpan) => {
        const span = this.processSpan({ otelSpan, trace });
        this.spans.set(otelSpan.spanContext().spanId, span);
      });
    });

    await this.client.flush();
    resultCallback({ code: 0 });
  };
}
```

**Usage**:

```typescript
import { generateText } from "ai";
import { openai } from "@ai-sdk/openai";
import { OpikExporter } from "opik-vercel";

const result = await generateText({
  model: openai("gpt-4"),
  prompt: "Hello!",
  experimental_telemetry: OpikExporter.getSettings({
    name: "my-generation",
  }),
});
```

**Advantages**:
- Leverages standard telemetry
- Works with any OTel-instrumented framework
- Rich span metadata

**Tradeoffs**:
- Requires OTel setup
- More complex configuration

## Existing Integrations

### OpenAI Integration (`opik-openai`)

**Supported Operations**:
- `chat.completions.create()` (streaming and non-streaming)
- `completions.create()`
- All nested API methods

**Captured Data**:

| Field | Source |
|-------|--------|
| `input` | Request messages/prompt |
| `output` | Response content |
| `model` | Request model parameter |
| `usage` | Token counts from response |
| `provider` | `"openai"` |

**Streaming Support**:

```typescript
const stream = await openai.chat.completions.create({
  model: "gpt-4",
  messages: [{ role: "user", content: "Hello!" }],
  stream: true,
});

// Stream is automatically tracked
for await (const chunk of stream) {
  process.stdout.write(chunk.choices[0]?.delta?.content || "");
}
```

### LangChain Integration (`opik-langchain`)

**Supported Components**:
- LLMs and Chat Models
- Chains
- Tools
- Retrievers
- Agents

**Callback Events**:

| Event | Span Type | Captured Data |
|-------|-----------|---------------|
| `handleLLMStart/End` | `llm` | prompts, generations, tokens |
| `handleChainStart/End` | `general` | chain input/output |
| `handleToolStart/End` | `tool` | tool name, args, result |
| `handleRetrieverStart/End` | `tool` | query, documents |
| `handleAgentAction/End` | `general` | actions, finish reason |

**Thread ID Support**:

```typescript
const handler = new OpikCallbackHandler({
  threadId: "conversation-123",
});
```

### Vercel AI Integration (`opik-vercel`)

**Supported Functions**:
- `generateText()`
- `generateObject()`
- `streamText()`
- `streamObject()`

**Telemetry Settings**:

```typescript
OpikExporter.getSettings({
  name: "trace-name",           // Custom trace name
  isEnabled: true,              // Enable/disable
  recordInputs: true,           // Capture inputs
  recordOutputs: true,          // Capture outputs
  metadata: { key: "value" },   // Custom metadata
});
```

### Gemini Integration (`opik-gemini`)

**Supported Operations**:
- `generateContent()`
- `generateContentStream()`
- Chat sessions

**Usage**:

```typescript
import { GoogleGenerativeAI } from "@google/generative-ai";
import { trackGemini } from "opik-gemini";

const genAI = trackGemini(new GoogleGenerativeAI(apiKey));
const model = genAI.getGenerativeModel({ model: "gemini-pro" });

const result = await model.generateContent("Hello!");
```

## Creating New Integrations

### Step 1: Create Package Structure

```bash
mkdir -p src/opik/integrations/opik-{name}
cd src/opik/integrations/opik-{name}
```

Create `package.json`:

```json
{
  "name": "opik-{name}",
  "version": "1.0.0",
  "description": "Opik integration for {Framework}",
  "main": "dist/index.cjs",
  "module": "dist/index.js",
  "types": "dist/index.d.ts",
  "type": "module",
  "exports": {
    ".": {
      "types": "./dist/index.d.ts",
      "import": "./dist/index.js",
      "require": "./dist/index.cjs"
    }
  },
  "peerDependencies": {
    "opik": "^1.0.0",
    "{framework-package}": "^x.x.x"
  }
}
```

### Step 2: Implement Wrapper/Handler

Choose the appropriate pattern based on framework:

**For SDK-style frameworks** (Proxy pattern):

```typescript
// src/track{Name}.ts
import { Opik } from "opik";

export interface Track{Name}Config {
  client?: Opik;
  projectName?: string;
}

export const track{Name} = <T extends object>(
  sdk: T,
  config?: Track{Name}Config
): T => {
  const client = config?.client ?? new Opik();

  return new Proxy(sdk, {
    get(target, prop, receiver) {
      const value = Reflect.get(target, prop, receiver);

      if (typeof value === "function") {
        return async (...args: unknown[]) => {
          const trace = client.trace({
            name: `${sdk.constructor.name}.${String(prop)}`,
            input: extractInput(args),
          });

          try {
            const result = await value.apply(target, args);
            trace.update({
              output: extractOutput(result),
            });
            trace.end();
            return result;
          } catch (error) {
            trace.update({ errorInfo: formatError(error) });
            trace.end();
            throw error;
          }
        };
      }

      return value;
    },
  });
};
```

**For callback-based frameworks**:

```typescript
// src/{Name}Handler.ts
import { Opik, Trace, Span } from "opik";

export class {Name}Handler implements FrameworkCallbackInterface {
  private client: Opik;
  private traces = new Map<string, Trace>();
  private spans = new Map<string, Span>();

  constructor(config?: { client?: Opik }) {
    this.client = config?.client ?? new Opik();
  }

  onStart(event: StartEvent): void {
    const trace = this.client.trace({
      name: event.name,
      input: event.input,
    });
    this.traces.set(event.id, trace);
  }

  onEnd(event: EndEvent): void {
    const trace = this.traces.get(event.id);
    if (trace) {
      trace.update({ output: event.output });
      trace.end();
    }
  }

  async flush(): Promise<void> {
    await this.client.flush();
  }
}
```

### Step 3: Add Response Parsing

```typescript
// src/parsers.ts
export function extractInput(args: unknown[]): Record<string, unknown> {
  // Framework-specific input extraction
  const [options] = args;
  return {
    messages: options?.messages,
    model: options?.model,
    // ...
  };
}

export function extractOutput(response: unknown): Record<string, unknown> {
  // Framework-specific output extraction
  return {
    content: response?.choices?.[0]?.message?.content,
    // ...
  };
}

export function extractUsage(response: unknown): Record<string, number> {
  return {
    prompt_tokens: response?.usage?.prompt_tokens ?? 0,
    completion_tokens: response?.usage?.completion_tokens ?? 0,
    total_tokens: response?.usage?.total_tokens ?? 0,
  };
}
```

### Step 4: Export Public API

```typescript
// src/index.ts
export { track{Name} } from "./track{Name}";
export type { Track{Name}Config } from "./track{Name}";
```

### Step 5: Add Tests

```typescript
// tests/integration.test.ts
import { describe, it, expect, beforeEach } from "vitest";
import { track{Name} } from "../src";
import { server } from "./mockUtils";

describe("opik-{name}", () => {
  it("captures trace for basic call", async () => {
    const sdk = track{Name}(new FrameworkSDK());
    
    await sdk.someMethod({ input: "test" });
    await sdk.flush();

    // Verify captured data
  });
});
```

## Streaming Support

### Handling Async Iterables

```typescript
async function* wrapAsyncIterable<T>(
  iterable: AsyncIterable<T>,
  onChunk: (chunk: T) => void,
  onComplete: (chunks: T[]) => void
): AsyncIterable<T> {
  const chunks: T[] = [];

  for await (const chunk of iterable) {
    chunks.push(chunk);
    onChunk(chunk);
    yield chunk;
  }

  onComplete(chunks);
}
```

### Accumulating Streamed Responses

```typescript
function accumulateStreamedContent(chunks: StreamChunk[]): string {
  return chunks
    .map((chunk) => chunk.choices?.[0]?.delta?.content ?? "")
    .join("");
}

function accumulateStreamedUsage(chunks: StreamChunk[]): Usage {
  // Usage typically comes in final chunk
  const lastChunk = chunks[chunks.length - 1];
  return lastChunk?.usage ?? { prompt_tokens: 0, completion_tokens: 0 };
}
```

### Complete Streaming Example

```typescript
// In decorator/wrapper
if (isAsyncIterable(result)) {
  return wrapAsyncIterable(
    result,
    (chunk) => {
      // Optional: process each chunk
    },
    (allChunks) => {
      span.update({
        output: { content: accumulateStreamedContent(allChunks) },
        usage: accumulateStreamedUsage(allChunks),
      });
      span.end();
    }
  );
}
```
