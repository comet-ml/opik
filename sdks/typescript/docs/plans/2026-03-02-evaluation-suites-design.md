# Evaluation Suites — TypeScript SDK Design

**Date:** 2026-03-02
**Ticket:** OPIK-4669
**Branch:** `awkoy/OPIK-4669-ts-sdk-eval-suites`
**Scope:** Full parity with Python SDK evaluation suites

## Overview

Evaluation suites provide regression testing for LLM applications. A suite is a collection of test cases (items), each with assertions evaluated by an LLM judge. Suites support configurable execution policies (multiple runs per item, pass thresholds) and produce deterministic pass/fail results.

The Python SDK already implements this feature. This design brings full parity to the TypeScript SDK.

## Approach

**Mirror Python's architecture (Approach A):** Create separate `suite_evaluators/` and `suite/` directories. Keep suite evaluators completely separate from existing metrics. This is additive, low-risk, and maintains cross-SDK consistency.

---

## 1. File Structure

### New Files

```
src/opik/
├── evaluation/
│   ├── suite/
│   │   ├── EvaluationSuite.ts        # Main EvaluationSuite class
│   │   ├── evaluateSuite.ts          # evaluateSuite() function
│   │   ├── types.ts                  # EvaluationSuiteResult, ItemResult
│   │   ├── suiteResultConstructor.ts # Pass/fail logic
│   │   └── index.ts                  # Barrel exports
│   └── suite_evaluators/
│       ├── BaseSuiteEvaluator.ts     # Abstract base with toConfig/fromConfig
│       ├── LLMJudge.ts              # LLMJudge evaluator class
│       ├── llmJudgeConfig.ts        # Config schema (Zod-based)
│       ├── llmJudgeParsers.ts       # Response format builder + parser
│       ├── llmJudgeTemplate.ts      # Prompt template constants
│       ├── validators.ts            # Validate evaluators are LLMJudge
│       └── index.ts                 # Barrel exports
```

### Modified Files

```
src/opik/
├── client/Client.ts                  # +3 methods: createEvaluationSuite, getEvaluationSuite, getOrCreateEvaluationSuite
├── dataset/Dataset.ts                # +2 methods: getEvaluators(), getExecutionPolicy()
├── evaluation/engine/EvaluationEngine.ts  # Support runsPerItem, item-level evaluators, trialId
├── evaluation/types.ts               # Add trialId to EvaluationTestResult
├── index.ts                          # Export new types and classes
```

### New Test Files

```
tests/
├── unit/
│   ├── evaluation/
│   │   └── suite/
│   │       ├── suiteResultConstructor.test.ts
│   │       └── evaluateSuite.test.ts
│   └── suite_evaluators/
│       ├── LLMJudge.test.ts
│       ├── llmJudgeParsers.test.ts
│       └── validators.test.ts
├── evaluation/
│   └── evaluateSuite.test.ts         # Component-level tests
```

### Documentation

```
apps/opik-documentation/.../reference/typescript-sdk/evaluation/
├── evaluation-suites.mdx             # New user-facing doc page
```

---

## 2. Prerequisites & Blockers

### Fern SDK Regeneration Required

The `ExperimentWrite` type in the TS REST API is **missing the `evaluationMethod` field**:

```typescript
// Current ExperimentWrite — no evaluationMethod
export interface ExperimentWrite {
    id?: string;
    datasetName: string;
    name?: string;
    // ... no evaluationMethod field
}
```

The backend migration `000061_add_evaluation_method_to_experiments.sql` exists and the OpenAPI spec includes it, but the Fern-generated TS types haven't been regenerated. **We must regenerate the REST client from the updated OpenAPI spec before implementing `evaluateSuite()`**, so experiments can be tagged with `evaluationMethod: "evaluation_suite"`.

---

## 3. Client API

### OpikClient New Methods

#### `createEvaluationSuite(options)`

Creates a new evaluation suite backed by a dataset with `type: "evaluation_suite"`.

**Parameters:**
- `name: string` — Suite name (becomes the dataset name)
- `description?: string` — Optional description
- `evaluators?: LLMJudge[]` — Suite-level evaluators applied to all items
- `executionPolicy?: ExecutionPolicy` — Suite-level execution policy

**Behavior:**
1. Validates all evaluators are `LLMJudge` instances
2. Creates dataset via `opik.api.datasets.createDataset({ name, type: "evaluation_suite", description })`
3. Creates initial version via `opik.api.datasets.applyDatasetItemChanges(datasetId, { body: { evaluators, execution_policy }, override: true })`
4. Returns `EvaluationSuite` instance wrapping the dataset

**REST operations detail:**
- Initial creation uses `override: true` to force version creation
- Evaluators serialized as: `[{ name: e.name, type: "llm_judge", config: e.toConfig() }]`
- Execution policy serialized as: `{ runs_per_item: N, pass_threshold: N }`
- The `DatasetItemChangesPublic` type is `Record<string, unknown>` in TS — body constructed manually

#### `getEvaluationSuite(name)`

Retrieves an existing evaluation suite by name.

**Parameters:**
- `name: string` — Suite name to look up

**Behavior:**
- Fetches dataset via `opik.api.datasets.getDatasetByIdentifier({ datasetName: name })`
- Syncs hashes via `dataset.syncHashes()` (reuse existing Dataset pattern)
- Throws if not found (404)
- Returns `EvaluationSuite` wrapping the dataset

#### `getOrCreateEvaluationSuite(options)`

Gets existing suite or creates new one.

**Parameters:** Same as `createEvaluationSuite()`

**Behavior:**
- Tries `getEvaluationSuite(name)`
- On 404, falls back to `createEvaluationSuite()` with provided params
- Returns `EvaluationSuite`

---

## 4. EvaluationSuite Class

Wraps a `Dataset` instance, providing suite-specific operations.

### Properties

- `name: string` (readonly) — Suite name (delegates to `dataset.name`)
- `description: string | undefined` (readonly) — Suite description

### Methods

#### `addItem(data, options?)`

Adds a test case to the suite.

- `data: Record<string, unknown>` — Test case data (passed to task function)
- `options.evaluators?: LLMJudge[]` — Item-level evaluators (extend suite-level, not replace)
- `options.executionPolicy?: ExecutionPolicy` — Item-level execution policy override

**Serialization detail:** Converts each `LLMJudge` to `EvaluatorItemWrite` format:
```typescript
{
  name: evaluator.name,
  type: "llm_judge",
  config: evaluator.toConfig()  // Must use camelCase aliases matching backend format
}
```

Uses `dataset.__internal_api__insert_items_as_dataclasses__` pattern or constructs `DatasetItemWrite` directly with `evaluators` and `executionPolicy` fields.

#### `run(task, options?)`

Executes the suite against a task function.

- `task: EvaluationTask` — Function processing each item
- `options.experimentName?: string`
- `options.experimentNamePrefix?: string`
- `options.projectName?: string`
- `options.experimentConfig?: Record<string, unknown>`
- `options.prompts?: Prompt[]`
- `options.experimentTags?: string[]`
- `options.verbose?: number` — Defaults to 1
- `options.workerThreads?: number` — Defaults to 16
- `options.evaluatorModel?: string` — Override model for all evaluators

**Returns:** `EvaluationSuiteResult`

Delegates to `evaluateSuite()`, then wraps raw result via `buildSuiteResult()`.

#### `getItems(evaluatorModel?)`

Returns all items with their evaluators and execution policies. Streams items from backend via existing `getDatasetItems()` helper. Deserializes `EvaluatorItem` configs back to `LLMJudge` instances using hardcoded dispatch (`type === "llm_judge"` → `LLMJudge.fromConfig()`).

Returns: `Array<{ id: string, data: Record<string, unknown>, evaluators: LLMJudge[], executionPolicy: ExecutionPolicy }>`

#### `getEvaluators(evaluatorModel?)`

Returns suite-level `LLMJudge[]` from dataset version metadata.

**Implementation:** Uses existing `dataset.getVersionInfo()` (calls `listDatasetVersions(id, { page: 1, size: 1 })`) to read `evaluators` from `DatasetVersionPublic`. Deserializes each item. Supports model override via `evaluatorModel` param passed to `LLMJudge.fromConfig(config, { model: evaluatorModel })`.

#### `getExecutionPolicy()`

Returns `ExecutionPolicy` from dataset version metadata.

**Implementation:** Uses existing `dataset.getVersionInfo()` to read `executionPolicy` from `DatasetVersionPublic`. Falls back to defaults `{ runsPerItem: 1, passThreshold: 1 }` for any missing field individually (not wholesale).

#### `update(options)`

Updates suite-level configuration. Creates a new dataset version.

- `options.evaluators: LLMJudge[]` — Required, new suite-level evaluators
- `options.executionPolicy: ExecutionPolicy` — Required, new execution policy

**REST detail:** Uses `applyDatasetItemChanges(datasetId, { body: { base_version: currentVersionId, evaluators, execution_policy }, override: false })`. The `override: false` preserves existing items while updating version-level config. The `base_version` is the UUID of the current latest version (fetched via `getVersionInfo()`).

#### `deleteItems(itemIds)`

Removes items from the suite by ID.

- `itemIds: string[]`

---

## 5. ExecutionPolicy Type

```typescript
type ExecutionPolicy = {
  runsPerItem?: number;   // How many times to execute each item (default: 1)
  passThreshold?: number; // Minimum passing runs for item to pass (default: 1)
};

const DEFAULT_EXECUTION_POLICY: ExecutionPolicy = {
  runsPerItem: 1,
  passThreshold: 1,
};
```

**Merging strategy for item-level overrides:** Each field is merged individually against the suite default. If an item sets `runsPerItem: 5` but omits `passThreshold`, it inherits `passThreshold` from the suite default. This is NOT a wholesale replacement.

---

## 6. LLMJudge Evaluator

### BaseSuiteEvaluator

Abstract class extending `BaseMetric`:

- `abstract toConfig(): LLMJudgeConfig` — Serialize for backend storage
- `static abstract fromConfig(config, options?): BaseSuiteEvaluator` — Reconstruct from stored config

### LLMJudge Class

**Constructor:**
- `assertions: string[]` — Assertion statements to evaluate
- `name?: string` — Defaults to `"llm_judge"`
- `model?: string` — Defaults to `"gpt-5-nano"` (matches Python SDK default)
- `track?: boolean` — Defaults to `true`
- `projectName?: string`
- `seed?: number`
- `temperature?: number`

**`score(input)` method:**
1. Builds prompt from template with `input`, `output`, and `assertions`
2. Builds dynamic Zod schema for structured response (one field per assertion: `score: boolean`, `reason: string`, `confidence: number`)
3. Calls LLM via Vercel AI SDK `generateObject()` (same as existing TS metrics)
4. Parses response into `ScoreResult[]` — one per assertion
5. On parse failure: returns `ScoreResult` with `scoringFailed: true`, `value: 0`

**`toConfig()` method:**
Serializes to `LLMJudgeConfig` matching Python's backend format. Field names must use **camelCase aliases** matching what Python produces with `model_dump(by_alias=True)`:
- `version: string` (e.g. "1")
- `name: string` (e.g. "llm_judge")
- `model: { name, temperature, seed, customParameters }` — note: `customParameters` not `custom_parameters`
- `messages: [{ role: "SYSTEM"|"USER", content }]`
- `variables: { input: "{{input}}", output: "{{output}}" }`
- `schema: [{ name: "<assertion>", type: "BOOLEAN", description }]` — note: `schema` not `schema_`

**`fromConfig(config, options?)` static method:**
- Reconstructs `LLMJudge` from stored config
- Extracts assertions from `schema` items (each schema item's `name` = assertion text)
- Supports `options.model` override (used when `evaluatorModel` is passed)

### Prompt Template

Matches Python SDK's template exactly:

**System:**
```
You are an expert judge tasked with evaluating if an AI agent's output satisfies a set of assertions.

For each assertion, provide:
- score: true if the assertion passes, false if it fails
- reason: A brief explanation of your judgment
- confidence: A float between 0.0 and 1.0 indicating how confident you are in your judgment
```

**User:**
```
## Input
{input}

## Output
{output}

## Assertions
Evaluate each of the following assertions against the agent's output:
{assertions}
```

### Response Format

Dynamic Zod schema generated per evaluation, one field per assertion:

```typescript
// For assertions: ["Response is helpful", "No hallucinations"]
z.object({
  "Response is helpful": z.object({
    score: z.boolean(),
    reason: z.string(),
    confidence: z.number().min(0).max(1),
  }),
  "No hallucinations": z.object({
    score: z.boolean(),
    reason: z.string(),
    confidence: z.number().min(0).max(1),
  }),
})
```

### LLM Integration

Uses Vercel AI SDK's `generateObject()` with structured output — same approach as existing TS metrics (GEval, AnswerRelevance, etc.). Uses `resolveModel()` from `modelsFactory.ts` for model instantiation. No new dependencies.

---

## 7. evaluateSuite() Function

Separate from `evaluate()`, reads configuration from the dataset rather than parameters.

**Signature:**
```typescript
evaluateSuite(options: {
  dataset: Dataset,
  task: EvaluationTask,
  experimentName?: string,
  experimentNamePrefix?: string,
  projectName?: string,
  experimentConfig?: Record<string, unknown>,
  prompts?: Prompt[],
  experimentTags?: string[],
  verbose?: number,
  workerThreads?: number,
  evaluatorModel?: string,
}) => Promise<EvaluationResult>
```

**Key differences from `evaluate()`:**
- No `scoringMetrics` param — metrics come from dataset version metadata
- No `nbSamples` — evaluates all items
- No `scoringKeyMapping` — always `undefined` for suites
- Creates experiment with `evaluationMethod: "evaluation_suite"` (requires Fern regeneration)

**Flow:**
1. Get/create OpikClient
2. Read suite evaluators via `dataset.getEvaluators(evaluatorModel)`
3. Read execution policy via `dataset.getExecutionPolicy()`
4. Create experiment with `evaluationMethod: "evaluation_suite"` and `datasetVersionId` from `dataset.getVersionInfo()`
5. Create `EvaluationEngine` with suite config (scoring metrics from dataset, execution policy for runs)
6. Execute and return raw `EvaluationResult`
7. Suite progress display should NOT show individual scores (matching Python's `show_scores_in_progress_bar=False`)

---

## 8. EvaluationEngine Modifications

### trialId Support

Add `trialId?: number` to `EvaluationTestResult`:
```typescript
type EvaluationTestResult = {
  testCase: EvaluationTestCase;
  scoreResults: EvaluationScoreResult[];
  trialId?: number;  // NEW: run index (0, 1, 2...) for multi-run suites
};
```

This is required for `buildSuiteResult()` to group runs by item and count passes.

### runsPerItem Support

When `runsPerItem > 1`, the engine executes each dataset item's task N times. For each dataset item:
```
for runId in range(0, runsPerItem):
  - Create a separate trace
  - Execute task
  - Run all applicable evaluators
  - Create ExperimentItemReference linking trace → dataset item
  - Store TestResult with trialId = runId
```

Each run produces an independent `EvaluationTestResult` with the same `datasetItemId` but different `traceId` and `trialId`.

### Item-Level Evaluators

Each dataset item can carry `evaluators` and `executionPolicy` fields in its data. The engine:
1. Extracts these fields from the streamed `DatasetItemPublic` object (they are top-level fields, not inside `data`)
2. Deserializes evaluator configs: hardcoded dispatch `type === "llm_judge"` → `LLMJudge.fromConfig(config, { model: evaluatorModel })` (matching Python)
3. **Appends** item-level evaluators to suite-level evaluators (NOT replacement — both run)
4. Resolves item-level `executionPolicy` with per-field fallback to suite default

### Execution Policy Resolution

For each item:
```typescript
function resolveItemExecutionPolicy(
  itemPolicy: ExecutionPolicy | undefined,
  defaultPolicy: ExecutionPolicy
): Required<ExecutionPolicy> {
  return {
    runsPerItem: itemPolicy?.runsPerItem ?? defaultPolicy.runsPerItem ?? 1,
    passThreshold: itemPolicy?.passThreshold ?? defaultPolicy.passThreshold ?? 1,
  };
}
```

---

## 9. Pass/Fail Logic

### `buildSuiteResult(evalResult: EvaluationResult): EvaluationSuiteResult`

**Algorithm:**
1. Group all test results by `testCase.datasetItemId`
2. For each item:
   - Extract `passThreshold` from item's resolved execution policy (default: 1)
   - For each run (identified by `trialId`): **passes** if no scores OR all score values are truthy (`value === true` or `value === 1`)
   - Count `runsPassed`
   - Item **passes** if `runsPassed >= passThreshold`
3. Suite **passes** if all items pass
4. `passRate = itemsPassed / itemsTotal` (1.0 if itemsTotal === 0)
5. Sort test results by `trialId` for deterministic ordering (matching Python)

### EvaluationSuiteResult

```typescript
type EvaluationSuiteResult = {
  allItemsPassed: boolean;
  itemsPassed: number;
  itemsTotal: number;
  passRate: number;                          // 0.0 to 1.0
  itemResults: Map<string, ItemResult>;
  experimentId: string;
  experimentName?: string;
  experimentUrl?: string;
};

type ItemResult = {
  datasetItemId: string;
  passed: boolean;
  runsPassed: number;
  runsTotal: number;
  passThreshold: number;
  testResults: EvaluationTestResult[];       // Reuse existing type
};
```

---

## 10. Validators

```typescript
function validateEvaluators(evaluators: unknown[], context: string): void
```

- Checks each evaluator is `instanceof LLMJudge`
- Throws `TypeError` if any non-LLMJudge evaluator is found
- `context` string included in error message (e.g. "suite-level evaluators", "item-level evaluators")
- Only `"llm_judge"` type is supported. The API also defines `"code_metric"` as a future extension point — not implemented now.

---

## 11. Dataset Extensions

Two new methods on the existing `Dataset` class:

### `getEvaluators(evaluatorModel?: string): Promise<LLMJudge[]>`

- Calls existing `this.getVersionInfo()` which uses `listDatasetVersions(id, { page: 1, size: 1 })`
- Reads `evaluators` array from `DatasetVersionPublic` response
- If no version or no evaluators, returns `[]`
- Deserializes each: `type === "llm_judge"` → `LLMJudge.fromConfig(item.config, { model: evaluatorModel })`
- Logs warning for unsupported evaluator types (matching Python)

### `getExecutionPolicy(): Promise<ExecutionPolicy>`

- Calls existing `this.getVersionInfo()`
- Reads `executionPolicy` from `DatasetVersionPublic` response
- Falls back per-field: `runsPerItem ?? 1`, `passThreshold ?? 1`
- Returns `DEFAULT_EXECUTION_POLICY` if no version or no policy

---

## 12. Public Exports

Add to `src/opik/index.ts`:

- `EvaluationSuite`
- `LLMJudge`
- `evaluateSuite`
- `EvaluationSuiteResult`
- `ItemResult`
- `ExecutionPolicy`
- `BaseSuiteEvaluator`

---

## 13. Documentation

### New Page: `evaluation-suites.mdx`

Contents:
- What evaluation suites are (regression testing for LLM apps)
- Creating a suite with evaluators and execution policy
- Adding items with optional item-level overrides
- Running a suite against a task function
- Understanding results (pass/fail, pass rate, per-item breakdown)
- Full working example

---

## 14. Test Plan

### Unit Tests (pure logic, mocked dependencies)

**`suiteResultConstructor.test.ts`** — Port Python's test cases:
- Single item, all assertions pass → item passes
- Single item, one assertion fails → run fails
- Multiple runs, threshold met → item passes
- Multiple runs, threshold not met → item fails
- No scores → run passes by default
- Integer scores (1 = pass, 0 = fail)
- Pass rate: all pass (1.0), none pass (0.0), partial, zero items (1.0)

**`LLMJudge.test.ts`:**
- `toConfig()` / `fromConfig()` round-trip preserves all fields
- Config format matches expected schema structure (camelCase aliases)
- Model override via `fromConfig` options works
- Assertions extracted correctly from schema items

**`llmJudgeParsers.test.ts`:**
- Dynamic schema generation from assertion list
- Successful parse → correct ScoreResults in order
- Parse failure → `scoringFailed: true` with value 0

**`validators.test.ts`:**
- LLMJudge instances pass validation
- Non-LLMJudge instances throw TypeError
- Mixed evaluators throw
- Existing BaseMetric instances (AnswerRelevance, etc.) throw

### Component Tests (mocked API)

**`evaluateSuite.test.ts`:**
- Creates experiment with `evaluationMethod: "evaluation_suite"`
- Reads evaluators from dataset version metadata
- Reads execution policy from dataset version metadata
- Executes task per item × runsPerItem
- Assigns trialId (0, 1, 2...) to each run
- Returns correct result structure

### Integration Tests (future, real backend)

- Full create → add items → run → verify results flow
- Round-trip: create suite → get suite → run
- Item deletion
- Suite update (new evaluators/policy creates new version)
- evaluators and executionPolicy survive persistence round-trip
- Item-level overrides work correctly

---

## 15. Usage Example

```typescript
import { Opik, LLMJudge } from "opik";

const client = new Opik();

// Create suite
const suite = await client.createEvaluationSuite({
  name: "Refund Policy Tests",
  description: "Regression tests for refund scenarios",
  evaluators: [
    new LLMJudge({
      assertions: [
        "Response does not contain hallucinated information",
        "Response is helpful to the user",
      ],
    }),
  ],
  executionPolicy: { runsPerItem: 3, passThreshold: 2 },
});

// Add items (item evaluators EXTEND suite evaluators, they don't replace)
await suite.addItem(
  { userInput: "How do I get a refund?", userTier: "premium" },
  {
    evaluators: [new LLMJudge({ assertions: ["Response is polite"] })],
    executionPolicy: { runsPerItem: 2, passThreshold: 1 },
  },
);

// Run
const result = await suite.run(
  async (item) => {
    const response = await callLLM(item.userInput);
    return { input: item, output: response };
  },
  { experimentName: "refund_policy_v1", evaluatorModel: "gpt-4o" },
);

// Check results
console.log(`Pass rate: ${result.passRate}`);
console.log(`Items: ${result.itemsPassed}/${result.itemsTotal}`);
for (const [id, itemResult] of result.itemResults) {
  console.log(`Item ${id}: ${itemResult.passed} (${itemResult.runsPassed}/${itemResult.runsTotal})`);
}
```

---

## 16. FYI: Reusable TS SDK Infrastructure

This section documents existing TS SDK code that can be directly reused for the evaluation suites implementation, avoiding unnecessary duplication.

### Dataset Class (`dataset/Dataset.ts`)

| What | How to Reuse |
|------|-------------|
| `getVersionInfo()` | Already fetches latest `DatasetVersionPublic` with `evaluators` and `executionPolicy` fields. Use directly in `getEvaluators()` and `getExecutionPolicy()`. |
| `syncHashes()` | Called by `getEvaluationSuite()` after fetching dataset from backend (matches Python's pattern). |
| `insert()` with deduplication | Suite's `addItem()` can reuse the item insertion path. Need to extend `DatasetItem.toApiModel()` to include `evaluators` and `executionPolicy` fields. |
| `getItems()` via streaming | Uses `getDatasetItems()` helper with NDJSON streaming. Streamed `DatasetItemPublic` already includes `evaluators` and `executionPolicy` fields in the REST type. |
| Batch processing | Existing `splitIntoBatches()` from `utils/stream.ts` for batch operations. |

### DatasetItem Class (`dataset/DatasetItem.ts`)

| What | How to Reuse |
|------|-------------|
| `DatasetItem` constructor | Auto-generates UUID via `generateId()`, sets `source: "sdk"`. |
| `toApiModel()` | Converts to `DatasetItemWrite`. **Needs extension** to include optional `evaluators` and `executionPolicy` fields. |
| `fromApiModel()` | Factory from `DatasetItemWrite`. **Needs extension** to preserve `evaluators` and `executionPolicy`. |
| `contentHash()` | Uses xxhash32 for dedup. Can be reused as-is for suite items. |

### Client (`client/Client.ts`)

| What | How to Reuse |
|------|-------------|
| `createDataset()` | Creates dataset via batch queue. For suites, we need direct REST call with `type: "evaluation_suite"` (batch queue doesn't support type param — need to check). |
| `getDataset()` / error handling | 404 → `DatasetNotFoundError` pattern. Reuse for `getEvaluationSuite()`. |
| `createExperiment()` | Existing method. **Needs extension** to accept optional `evaluationMethod` param (after Fern regen). |

### Evaluation Engine (`evaluation/engine/EvaluationEngine.ts`)

| What | How to Reuse |
|------|-------------|
| Trace creation pattern | `client.trace({ projectName, name: "evaluation_task", createdBy: "evaluation", input })` — reuse directly. |
| Task execution with `@track` | `track({ name: "llm_task", type: SpanType.General }, task)(item)` — reuse directly. |
| Score recording on traces | `rootTrace.score({ name, value, reason })` — reuse directly. |
| ExperimentItemReferences | Linking traces to dataset items — reuse directly. |
| `prepareScoringInputs()` | Merges dataset item + task output. Reuse, but note suites don't use `scoringKeyMapping`. |
| Result processing | `EvaluationResultProcessor.processResults()` for display. May need suite-specific variant that shows pass/fail instead of averages. |

### Model Infrastructure (`evaluation/models/`)

| What | How to Reuse |
|------|-------------|
| `resolveModel(modelId)` | Smart model resolution: string → model instance, handles defaults. Use for LLMJudge model instantiation. |
| `OpikBaseModel.generateString()` | Main LLM calling interface with structured output (Zod schema). Use in LLMJudge.score(). |
| `DEFAULT_MODEL = "gpt-5-nano"` | Same default as Python SDK. |

### Existing LLM Judge Metrics (`evaluation/metrics/llmJudges/`)

| What | How to Reuse |
|------|-------------|
| `BaseLLMJudgeMetric` | Has `model`, `temperature`, `seed`, `buildModelOptions()`. **Do NOT extend** for suite LLMJudge (different base class), but follow same patterns. |
| `extractJsonContentOrRaise()` | Robust JSON extraction from LLM responses (`parsingHelpers.ts`). Reuse in `llmJudgeParsers.ts`. |
| `model.generateString(prompt, responseSchema, options)` | Calling pattern. Suite LLMJudge should follow the same approach. |

### Utility Functions (`utils/`)

| Utility | Location | Use For |
|---------|----------|---------|
| `generateId()` | `utils/generateId.ts` | UUID generation for suite items |
| `splitIntoBatches()` | `utils/stream.ts` | Batch operations on items |
| `parseNdjsonStreamToArray()` | `utils/stream.ts` | Streaming item retrieval |
| `logger` | `utils/logger.ts` | Logging throughout suite operations |
| `getExperimentUrlById()` | `utils/url.ts` | Building `experimentUrl` for suite results |

### Error Types (`errors/`)

| Error | Use For |
|-------|---------|
| `OpikError` | Base for suite-specific errors |
| `DatasetNotFoundError` | Reuse in `getEvaluationSuite()` |
| `MetricComputationError` | Reuse for LLMJudge scoring failures |
| `JSONParsingError` | Reuse for LLMJudge response parsing failures |

### What Needs NEW Implementation (Not Reusable)

| Component | Why New |
|-----------|---------|
| `EvaluationSuite` class | New abstraction wrapping Dataset |
| `LLMJudge` evaluator | Different from existing metrics: assertions-based, serializable, multiple scores per call |
| `BaseSuiteEvaluator` | New abstract class with `toConfig()`/`fromConfig()` contract |
| `buildSuiteResult()` | New pass/fail aggregation logic |
| `evaluateSuite()` | New function (reads config from dataset, not params) |
| `trialId` support in engine | New loop for `runsPerItem` with run indexing |
| Item-level evaluator deserialization | New hardcoded dispatch in engine |
| Suite-specific progress display | New or modified result processor (no score display, show pass/fail) |
