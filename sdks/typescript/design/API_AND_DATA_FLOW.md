# Opik TypeScript SDK: API and Data Flow

## Table of Contents

- [Overview](#overview)
- [High-Level API](#high-level-api)
- [Core Architecture](#core-architecture)
- [Data Flow](#data-flow)
- [Batch Queue System](#batch-queue-system)
- [Context Management](#context-management)
- [Configuration](#configuration)
- [Performance Considerations](#performance-considerations)

## Overview

The Opik TypeScript SDK provides **lightweight, non-blocking tracing** for LLM applications in Node.js environments. The architecture prioritizes minimal performance impact through asynchronous operations, intelligent batching, and debounced queue processing.

### Key Design Goals

1. **Non-blocking**: User code doesn't wait for backend communication
2. **Type-safe**: Full TypeScript support with generics and Zod validation
3. **Dual module support**: Works with both ESM and CommonJS
4. **Integration-friendly**: Separate packages for framework integrations

### Technology Stack

- **Runtime**: Node.js 18+
- **Language**: TypeScript 5.x
- **Build**: tsup (ESM + CJS output)
- **Validation**: Zod
- **HTTP**: node-fetch
- **Logging**: tslog
- **Testing**: Vitest + MSW

## High-Level API

### Main Entry Point: `Opik`

The `Opik` class (exported as `OpikClient` internally) is the central entry point for all SDK operations.

**Location**: `src/opik/client/Client.ts`

```typescript
import { Opik } from "opik";

// Initialize client
const client = new Opik({
  apiKey: "your-api-key",           // Required for cloud
  apiUrl: "https://www.comet.com/opik/api",  // Optional
  projectName: "my-project",        // Optional: defaults to "Default Project"
  workspaceName: "my-workspace",    // Optional: defaults to "default"
});
```

#### Configuration Priority

Configuration is resolved in this order (highest to lowest):
1. **Constructor parameters** passed to `new Opik()`
2. **Environment variables** (`OPIK_API_KEY`, `OPIK_URL_OVERRIDE`, etc.)
3. **Configuration file** (`~/.opik.config`)
4. **Default values**

### Core API Methods

#### Manual Tracing

```typescript
// Create a trace
const trace = client.trace({
  name: "my_operation",
  input: { query: "What is AI?" },
  metadata: { version: "1.0" },
  tags: ["production"],
});

// Create a span within trace
const span = trace.span({
  name: "llm_call",
  type: "llm",           // Types: "llm", "tool", "general"
  input: { prompt: "..." },
});

// Update span with output
span.update({
  output: { response: "..." },
  usage: { prompt_tokens: 50, completion_tokens: 100, total_tokens: 150 },
});

// End span and trace
span.end();
trace.end();

// Add feedback scores
trace.score({
  name: "accuracy",
  value: 0.95,
  reason: "Accurate response",
});

// Ensure all data is sent
await client.flush();
```

#### Automatic Tracing with Decorators

```typescript
import { track } from "opik";

// Wrapper function pattern (recommended for non-class code)
const myFunction = track(async (input: string) => {
  return await processInput(input);
});

// With options
const llmCall = track(
  { name: "custom_name", type: "llm", projectName: "my-project" },
  async (prompt: string) => {
    return await callLLM(prompt);
  }
);

// TC39 decorator pattern (for classes)
class MyService {
  @track({ name: "process", type: "general" })
  async process(data: string) {
    return this.transform(data);
  }
}

// Legacy decorator pattern (experimentalDecorators: true)
class LegacyService {
  @track()
  async legacyMethod(input: string) {
    return input.toUpperCase();
  }
}
```

#### Resource Management

```typescript
// Datasets with TypeScript type safety
type QuestionAnswerItem = {
  question: string;
  answer: string;
  metadata?: {
    category: string;
    difficulty: string;
  };
};

const dataset = await client.createDataset<QuestionAnswerItem>(
  "my-dataset",
  "Question-Answer pairs for evaluation"
);

await dataset.insert([
  { 
    question: "What is AI?", 
    answer: "Artificial intelligence is...",
    metadata: { category: "tech", difficulty: "easy" }
  },
  { 
    question: "What is ML?", 
    answer: "Machine learning is...",
    metadata: { category: "tech", difficulty: "medium" }
  },
]);

// Retrieve dataset
const existingDataset = await client.getDataset<QuestionAnswerItem>("my-dataset");
const items = await existingDataset.getItems();

// Experiments
const experiment = await client.createExperiment({
  datasetName: "my-dataset",
  name: "experiment-v1",
  experimentConfig: { model: "gpt-4" },
});

// Prompts
const prompt = await client.createPrompt({
  name: "greeting",
  prompt: "Hello {{name}}, welcome to {{place}}!",
  type: "mustache",
});

const formatted = prompt.format({ name: "Alice", place: "Wonderland" });
// Returns: "Hello Alice, welcome to Wonderland!"
```

## Core Architecture

### Layer Overview

```
┌─────────────────────────────────────────────────────────────┐
│                      User Application                        │
├─────────────────────────────────────────────────────────────┤
│  High-Level API                                              │
│  ┌─────────┐ ┌─────────┐ ┌─────────┐ ┌──────────┐          │
│  │  Opik   │ │  track  │ │ Dataset │ │ evaluate │          │
│  │ Client  │ │decorator│ │  API    │ │   API    │          │
│  └────┬────┘ └────┬────┘ └────┬────┘ └────┬─────┘          │
├───────┼───────-───┼───────-───┼───────────┼────────────────┤
│  Core Layer                                                │
│  ┌────┴────┐ ┌────┴────┐ ┌────┴────┐ ┌────┴─────┐          │
│  │  Trace  │ │  Span   │ │ Dataset │ │Experiment│          │
│  │ Entity  │ │ Entity  │ │ Entity  │ │  Entity  │          │
│  └────┬────┘ └────┬────┘ └────┬────┘ └────┬─────┘          │
├───────┼──────────┼──────────┼───────────┼───────────────────┤
│  Batch Queue Layer                                           │
│  ┌────┴─────────┴─────────┴───────────┴─────┐              │
│  │           BatchQueue (debounced)          │              │
│  │  ┌────────┐ ┌────────┐ ┌────────────────┐│              │
│  │  │ Create │ │ Update │ │ Delete Queue   ││              │
│  │  │ Queue  │ │ Queue  │ │                ││              │
│  │  └────────┘ └────────┘ └────────────────┘│              │
│  └─────────────────┬─────────────────────────┘              │
├────────────────────┼────────────────────────────────────────┤
│  REST API Layer    │                                         │
│  ┌─────────────────┴─────────────────┐                      │
│  │     OpikApiClient (Fern)          │                      │
│  │  ┌──────┐ ┌──────┐ ┌───────────┐  │                      │
│  │  │Traces│ │Spans │ │ Datasets  │  │                      │
│  │  └──────┘ └──────┘ └───────────┘  │                      │
│  └───────────────────────────────────┘                      │
└─────────────────────────────────────────────────────────────┘
```

### Key Components

#### OpikClient (`src/opik/client/Client.ts`)

The main client class that:
- Manages configuration
- Creates Trace and Span instances
- Owns batch queues for each entity type
- Provides dataset, experiment, and prompt management

```typescript
export class OpikClient {
  public api: OpikApiClientTemp;           // REST API client
  public config: OpikConfig;               // Resolved configuration
  public spanBatchQueue: SpanBatchQueue;   // Batched span operations
  public traceBatchQueue: TraceBatchQueue; // Batched trace operations
  public spanFeedbackScoresBatchQueue: SpanFeedbackScoresBatchQueue;
  public traceFeedbackScoresBatchQueue: TraceFeedbackScoresBatchQueue;
  public datasetBatchQueue: DatasetBatchQueue;
  // ...
}
```

#### Trace (`src/opik/tracer/Trace.ts`)

Represents a single trace (top-level operation):

```typescript
export class Trace {
  private spans: Span[] = [];
  
  constructor(
    public data: SavedTrace,
    private opik: OpikClient
  ) {}

  public span(spanData: SpanData): Span { /* ... */ }
  public update(updates: TraceUpdateData): this { /* ... */ }
  public end(): this { /* ... */ }
  public score(score: ScoreData): void { /* ... */ }
}
```

#### Span (`src/opik/tracer/Span.ts`)

Represents a span within a trace:

```typescript
export class Span {
  private childSpans: Span[] = [];
  
  constructor(
    public data: SavedSpan,
    private opik: OpikClient
  ) {}

  public span(spanData: SpanData): Span { /* ... */ }  // Nested spans
  public update(updates: SpanUpdateData): this { /* ... */ }
  public end(): this { /* ... */ }
  public score(score: ScoreData): void { /* ... */ }
}
```

## Data Flow

### Trace Creation Flow

```
User calls client.trace()
         │
         ▼
┌─────────────────────┐
│ Generate UUID (v7)  │
│ Set startTime       │
│ Resolve projectName │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│ Create Trace object │
│ with data snapshot  │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│ traceBatchQueue     │
│   .create(data)     │
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│ Add to createQueue  │
│ Start debounce timer│
└──────────┬──────────┘
           │
           ▼
┌─────────────────────┐
│ Return Trace to user│
│ (immediate return)  │
└─────────────────────┘
```

### Decorator Data Flow

```
@track decorated function called
              │
              ▼
┌──────────────────────────┐
│ Check AsyncLocalStorage  │
│ for parent context       │
└───────────┬──────────────┘
            │
    ┌───────┴───────┐
    │               │
    ▼               ▼
No parent       Has parent
    │               │
    ▼               ▼
┌─────────┐   ┌─────────────┐
│ Create  │   │ Create span │
│ trace + │   │ under parent│
│ span    │   │ span/trace  │
└────┬────┘   └──────┬──────┘
     │               │
     └───────┬───────┘
             │
             ▼
┌─────────────────────────┐
│ trackStorage.run()      │
│ Execute with new context│
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│ Capture input args      │
│ Update span.input       │
└───────────┬─────────────┘
            │
            ▼
┌─────────────────────────┐
│ Execute original fn     │
└───────────┬─────────────┘
            │
    ┌───────┴───────┐
    │               │
Success          Error
    │               │
    ▼               ▼
┌─────────┐   ┌─────────────┐
│ Capture │   │ Capture     │
│ output  │   │ errorInfo   │
│ + end   │   │ + end       │
└─────────┘   └─────────────┘
```

## Batch Queue System

### Architecture

The SDK uses a debounced batch queue system to minimize API calls while ensuring data is sent promptly.

**Location**: `src/opik/client/BatchQueue.ts`

```typescript
abstract class BatchQueue<EntityData, EntityId> {
  private createQueue: ActionQueue<EntityData, EntityId>;
  private updateQueue: ActionQueue<Partial<EntityData>, EntityId>;
  private deleteQueue: ActionQueue<void, EntityId>;
  
  // Subclasses implement these
  protected abstract createEntities(entities: EntityData[]): Promise<void>;
  protected abstract updateEntity(id: EntityId, updates: Partial<EntityData>): Promise<void>;
  protected abstract deleteEntities(ids: EntityId[]): Promise<void>;
}
```

### Queue Behavior

```
Item added to queue
        │
        ▼
┌───────────────────┐
│ Check batch size  │
│ (default: 100)    │
└────────┬──────────┘
         │
    ┌────┴────┐
    │         │
  Full    Not Full
    │         │
    ▼         ▼
┌───────┐ ┌────────────────┐
│ Flush │ │ Reset debounce │
│ now   │ │ timer (300ms)  │
└───────┘ └───────┬────────┘
                  │
                  ▼
          ┌───────────────┐
          │ Timer expires │
          │ → Flush queue │
          └───────────────┘
```

### Configuration Options

```typescript
interface BatchQueueOptions {
  delay?: number;              // Debounce delay (default: 300ms)
  enableCreateBatch?: boolean; // Batch creates (default: true)
  enableUpdateBatch?: boolean; // Batch updates (default: false)
  enableDeleteBatch?: boolean; // Batch deletes (default: true)
  createBatchSize?: number;    // Max items per batch (default: 100)
}
```

### Specialized Queues

| Queue | Purpose | Batch API Endpoint |
|-------|---------|-------------------|
| `TraceBatchQueue` | Trace create/update | `POST /v1/private/traces/batch` |
| `SpanBatchQueue` | Span create/update | `POST /v1/private/spans/batch` |
| `TraceFeedbackScoresBatchQueue` | Trace scores | `PUT /v1/private/traces/feedback-scores` |
| `SpanFeedbackScoresBatchQueue` | Span scores | `PUT /v1/private/spans/feedback-scores` |
| `DatasetBatchQueue` | Dataset create/delete | `POST /v1/private/datasets` |

## Context Management

### AsyncLocalStorage Pattern

The SDK uses Node.js `AsyncLocalStorage` to propagate trace/span context through async call chains.

**Location**: `src/opik/decorators/track.ts`

```typescript
import { AsyncLocalStorage } from "node:async_hooks";

type TrackContext = {
  span?: Span;
  trace?: Trace;
};

export const trackStorage = new AsyncLocalStorage<TrackContext>();

export const getTrackContext = (): Required<TrackContext> | undefined => {
  const { span, trace } = trackStorage.getStore() || {};
  if (!span || !trace) return undefined;
  return { span, trace };
};
```

### Context Propagation

```typescript
// Inside track decorator
return trackStorage.run({ span, trace }, () => {
  // All code here (including nested async calls)
  // can access the context via getTrackContext()
  return originalFn.apply(this, args);
});
```

### Nested Tracking Example

```typescript
const outer = track(async () => {
  // Context: { trace: T1, span: S1 }
  await inner();  // inner() sees parent context
});

const inner = track(async () => {
  // Context: { trace: T1, span: S2 (child of S1) }
  await deepest();
});

const deepest = track(async () => {
  // Context: { trace: T1, span: S3 (child of S2) }
});
```

## Configuration

### Configuration Loading

**Location**: `src/opik/config/Config.ts`

```typescript
export function loadConfig(explicit?: Partial<OpikConfig>): OpikConfig {
  const envConfig = loadFromEnv();
  const fileConfig = loadFromConfigFile();
  
  return validateConfig({
    ...DEFAULT_CONFIG,
    ...fileConfig,
    ...envConfig,
    ...explicit,  // Highest priority
  });
}
```

### Environment Variables

| Variable | Purpose | Default |
|----------|---------|---------|
| `OPIK_API_KEY` | API authentication | (none) |
| `OPIK_URL_OVERRIDE` | API base URL | `https://www.comet.com/opik/api` |
| `OPIK_PROJECT_NAME` | Default project | `Default Project` |
| `OPIK_WORKSPACE` | Workspace name | `default` |
| `OPIK_BATCH_DELAY_MS` | Queue debounce | `300` |
| `OPIK_HOLD_UNTIL_FLUSH` | Disable auto-flush | `false` |
| `OPIK_CONFIG_PATH` | Config file path | `~/.opik.config` |
| `OPIK_LOG_LEVEL` | Logging level | `INFO` |

### Config File Format

```ini
[opik]
api_key = your-api-key
url_override = https://www.comet.com/opik/api
project_name = my-project
workspace = my-workspace
```

## Performance Considerations

### Debounced Batching

The default 300ms debounce delay balances:
- **Latency**: Data reaches backend within ~300ms of last operation
- **Efficiency**: Multiple operations in quick succession are batched
- **Throughput**: Batch size of 100 prevents oversized requests

### Memory Management

- Queues are cleared after successful flush
- Failed batches are logged but not retried (fire-and-forget pattern)
- No unbounded queue growth

### ID Generation

Uses UUID v7 for time-ordered, unique identifiers:

```typescript
import { v7 as uuid } from "uuid";
export const generateId = () => uuid();
```

### Flush Behavior

```typescript
// Explicit flush - waits for all queues
await client.flush();

// Automatic flush on debounce timer
// Triggered 300ms after last queue operation
```
