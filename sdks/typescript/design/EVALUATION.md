# Opik TypeScript SDK Evaluation Guide

## Table of Contents

- [Overview](#overview)
- [Evaluation Architecture](#evaluation-architecture)
- [Evaluation Methods](#evaluation-methods)
- [Metrics Architecture](#metrics-architecture)
- [Built-in Metrics](#built-in-metrics)
- [Creating Custom Metrics](#creating-custom-metrics)
- [Model Abstraction](#model-abstraction)
- [Scoring Key Mapping](#scoring-key-mapping)

## Overview

The TypeScript SDK provides a comprehensive evaluation framework for assessing LLM task performance against datasets. The evaluation system supports both heuristic and LLM-based metrics.

### Key Features

- **Dataset-driven evaluation**: Run tasks against structured datasets
- **Multiple metric types**: Heuristic (rule-based) and LLM judge metrics
- **Prompt evaluation**: Specialized support for evaluating prompt templates
- **Experiment tracking**: Automatic experiment creation and result logging
- **Flexible scoring**: Custom key mapping between dataset and metrics

## Evaluation Architecture

### Component Overview

```
┌─────────────────────────────────────────────────────────────┐
│                     User Application                         │
├─────────────────────────────────────────────────────────────┤
│  Evaluation API                                              │
│  ┌─────────────────┐  ┌─────────────────────┐               │
│  │    evaluate()   │  │  evaluatePrompt()   │               │
│  └────────┬────────┘  └──────────┬──────────┘               │
├───────────┼──────────────────────┼──────────────────────────┤
│  Evaluation Engine                                           │
│  ┌────────┴──────────────────────┴────────┐                 │
│  │           EvaluationEngine             │                 │
│  │  ┌──────────┐ ┌──────────┐ ┌────────┐  │                 │
│  │  │ Dataset  │ │   Task   │ │Metrics │  │                 │
│  │  │ Iterator │ │ Executor │ │ Runner │  │                 │
│  │  └──────────┘ └──────────┘ └────────┘  │                 │
│  └────────────────────────────────────────┘                 │
├─────────────────────────────────────────────────────────────┤
│  Metrics Layer                                               │
│  ┌─────────────┐  ┌─────────────────────────┐               │
│  │ Heuristics  │  │      LLM Judges         │               │
│  │ ┌─────────┐ │  │ ┌───────────────────┐   │               │
│  │ │Contains │ │  │ │  AnswerRelevance  │   │               │
│  │ │ExactMatch│ │  │ │  Hallucination   │   │               │
│  │ │ IsJson  │ │  │ │   Moderation     │   │               │
│  │ │RegexMatch│ │  │ │   Usefulness    │   │               │
│  │ └─────────┘ │  │ └───────────────────┘   │               │
│  └─────────────┘  └─────────────────────────┘               │
├─────────────────────────────────────────────────────────────┤
│  Model Abstraction                                           │
│  ┌─────────────────────────────────────────┐                │
│  │           OpikBaseModel                  │                │
│  │  ┌─────────┐ ┌─────────┐ ┌───────────┐  │                │
│  │  │ OpenAI  │ │ Anthropic│ │  Gemini   │  │                │
│  │  └─────────┘ └─────────┘ └───────────┘  │                │
│  └─────────────────────────────────────────┘                │
└─────────────────────────────────────────────────────────────┘
```

### Directory Structure

```
src/opik/evaluation/
├── index.ts                    # Public exports
├── evaluate.ts                 # Main evaluate() function
├── evaluatePrompt.ts           # evaluatePrompt() function
├── types.ts                    # Type definitions
├── engine/
│   ├── EvaluationEngine.ts     # Core evaluation orchestration
│   └── index.ts
├── metrics/
│   ├── index.ts
│   ├── BaseMetric.ts           # Abstract base class
│   ├── argumentsValidator.ts   # Zod validation helpers
│   ├── errors.ts               # Metric errors
│   ├── heuristics/
│   │   ├── index.ts
│   │   ├── Contains.ts
│   │   ├── ExactMatch.ts
│   │   ├── IsJson.ts
│   │   └── RegexMatch.ts
│   └── llmJudges/
│       ├── index.ts
│       ├── BaseLLMJudgeMetric.ts
│       ├── types.ts
│       ├── parsingHelpers.ts
│       ├── answerRelevance/
│       ├── hallucination/
│       ├── moderation/
│       └── usefulness/
├── models/
│   ├── index.ts
│   ├── OpikBaseModel.ts        # Model abstraction
│   └── ...
├── results/
│   └── EvaluationResultProcessor.ts
└── utils/
    └── formatMessages.ts
```

## Evaluation Methods

### Method 1: `evaluate()` - Task-Based Evaluation

The primary evaluation method that runs a custom task function against a dataset.

**Location**: `src/opik/evaluation/evaluate.ts`

```typescript
interface EvaluateOptions<T> {
  dataset: Dataset<T>;                    // Dataset to evaluate
  task: EvaluationTask<T>;                // Task function
  scoringMetrics?: BaseMetric[];          // Metrics to apply
  experimentName?: string;                // Experiment name
  projectName?: string;                   // Project for traces
  experimentConfig?: Record<string, unknown>;  // Config metadata
  prompts?: Prompt[];                     // Linked prompts
  nbSamples?: number;                     // Limit samples
  client?: OpikClient;                    // Custom client
  scoringKeyMapping?: ScoringKeyMappingType;  // Key remapping
}

type EvaluationTask<T> = (
  datasetItem: T
) => Promise<Record<string, unknown>> | Record<string, unknown>;
```

**Usage**:

```typescript
import { Opik, evaluate } from "opik";
import { Contains, ExactMatch } from "opik/evaluation/metrics";

const client = new Opik();
const dataset = await client.getDataset("qa-dataset");

const result = await evaluate({
  dataset,
  task: async (item) => {
    const response = await myLLM.generate(item.input);
    return { output: response };
  },
  scoringMetrics: [
    new Contains(),
    new ExactMatch(),
  ],
  experimentName: "qa-eval-v1",
  experimentConfig: {
    model: "gpt-4",
    temperature: 0.7,
  },
});

console.log(`Experiment: ${result.experimentName}`);
console.log(`Results: ${result.results.length} items evaluated`);
```

### Method 2: `evaluatePrompt()` - Prompt Template Evaluation

Specialized evaluation for prompt templates with automatic model invocation.

**Location**: `src/opik/evaluation/evaluatePrompt.ts`

```typescript
interface EvaluatePromptOptions extends Omit<EvaluateOptions, "task"> {
  messages: OpikMessage[];                // Prompt template
  model?: SupportedModelId | LanguageModel | OpikBaseModel;  // Model
  templateType?: PromptType;              // "mustache" | "jinja2"
  temperature?: number;                   // Generation temp
  seed?: number;                          // Reproducibility
}
```

**Usage**:

```typescript
import { evaluatePrompt } from "opik/evaluation";
import { AnswerRelevance } from "opik/evaluation/metrics";

const result = await evaluatePrompt({
  dataset,
  messages: [
    { role: "system", content: "You are a helpful assistant." },
    { role: "user", content: "Answer this question: {{question}}" },
  ],
  model: "gpt-4o",
  temperature: 0.3,
  scoringMetrics: [new AnswerRelevance()],
  experimentName: "prompt-eval-v1",
});
```

### Evaluation Flow

```
evaluate() called
       │
       ▼
┌──────────────────┐
│ Create Experiment│
│ in Opik backend  │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ Get dataset items│
│ (with nbSamples) │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ For each item:   │
│ ┌──────────────┐ │
│ │ Execute task │ │
│ │ (with trace) │ │
│ └──────┬───────┘ │
│        │         │
│        ▼         │
│ ┌──────────────┐ │
│ │ Run metrics  │ │
│ │ on output    │ │
│ └──────┬───────┘ │
│        │         │
│        ▼         │
│ ┌──────────────┐ │
│ │ Log scores   │ │
│ │ to experiment│ │
│ └──────────────┘ │
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ Return results   │
│ with aggregates  │
└──────────────────┘
```

## Metrics Architecture

### BaseMetric Abstract Class

**Location**: `src/opik/evaluation/metrics/BaseMetric.ts`

```typescript
import { z } from "zod";

export abstract class BaseMetric<
  T extends z.ZodObject<z.ZodRawShape> = z.ZodObject<z.ZodRawShape>
> {
  public readonly name: string;
  public readonly trackMetric: boolean;
  public abstract readonly validationSchema: T;

  protected constructor(name: string, trackMetric = true) {
    this.name = name;
    this.trackMetric = trackMetric;

    // Auto-wrap with tracing if enabled
    if (trackMetric) {
      const originalScore = this.score.bind(this);
      this.score = track(
        { name: this.name, type: SpanType.General },
        originalScore
      );
    }
  }

  abstract score(
    input: unknown
  ):
    | EvaluationScoreResult
    | EvaluationScoreResult[]
    | Promise<EvaluationScoreResult>
    | Promise<EvaluationScoreResult[]>;
}
```

### Score Result Types

```typescript
interface EvaluationScoreResult {
  name: string;           // Metric name
  value: number;          // Score value (typically 0-1)
  reason?: string;        // Optional explanation
  metadata?: Record<string, unknown>;  // Additional data
}
```

### Validation with Zod

Each metric defines a Zod schema that serves two purposes:

1. **TypeScript type inference** - Generate types for the score method
2. **Runtime validation** - The evaluation engine validates inputs before calling `score()`

**Location**: `src/opik/evaluation/metrics/argumentsValidator.ts`

```typescript
// Metric defines the schema
class MyMetric extends BaseMetric {
  public readonly validationSchema = z.object({
    output: z.string(),
    expected: z.string(),
  });

  // Type is inferred from schema
  score(input: unknown): EvaluationScoreResult {
    // Input is already validated by the evaluation engine
    const { output, expected } = input as z.infer<typeof this.validationSchema>;
    // ...
  }
}
```

**Validation Flow**:

```
EvaluationEngine.calculateScores()
       │
       ▼
┌──────────────────────────────┐
│ validateRequiredArguments()  │  ← Uses metric.validationSchema
│ - Check all required keys    │
│ - Generate helpful errors    │
└──────────┬───────────────────┘
           │ (if valid)
           ▼
┌──────────────────────────────┐
│ metric.score(scoringInputs)  │  ← Receives validated input
└──────────────────────────────┘
```

**Error Handling**: If validation fails, the metric is skipped and a descriptive error is logged:

```
Metric 'contains_metric' is skipped, missing required arguments: substring.
Available arguments: output, input, context.
```

## Built-in Metrics

### Heuristic Metrics

#### Contains

Checks if output contains expected substring.

```typescript
import { Contains } from "opik/evaluation/metrics";

const metric = new Contains();
const result = metric.score({
  output: "The answer is 42",
  substring: "42",
});
// { name: "contains", value: 1.0 }
```

#### ExactMatch

Checks for exact string equality.

```typescript
import { ExactMatch } from "opik/evaluation/metrics";

const metric = new ExactMatch();
const result = metric.score({
  output: "hello",
  expected: "hello",
});
// { name: "exact_match", value: 1.0 }
```

#### IsJson

Validates if output is valid JSON.

```typescript
import { IsJson } from "opik/evaluation/metrics";

const metric = new IsJson();
const result = metric.score({
  output: '{"key": "value"}',
});
// { name: "is_json_metric", value: 1.0 }
```

#### RegexMatch

Matches output against a regular expression.

```typescript
import { RegexMatch } from "opik/evaluation/metrics";

const metric = new RegexMatch({ pattern: "\\d{4}-\\d{2}-\\d{2}" });
const result = metric.score({
  output: "Date: 2024-01-15",
});
// { name: "regex_match_metric", value: 1.0 }
```

### LLM Judge Metrics

LLM-based metrics use language models to evaluate outputs.

#### AnswerRelevance

Evaluates how relevant the answer is to the question.

```typescript
import { AnswerRelevance } from "opik/evaluation/metrics";

const metric = new AnswerRelevance({
  model: "gpt-4o",
});

const result = await metric.score({
  input: "What is the capital of France?",
  output: "Paris is the capital of France.",
});
// { name: "answer_relevance", value: 0.95, reason: "..." }
```

#### Hallucination

Detects factual hallucinations in outputs.

```typescript
import { Hallucination } from "opik/evaluation/metrics";

const metric = new Hallucination({
  model: "gpt-4o",
});

const result = await metric.score({
  input: "What year was Python created?",
  output: "Python was created in 1991.",
  context: ["Python was first released in 1991 by Guido van Rossum."],
});
// { name: "hallucination", value: 0.0 } // 0 = no hallucination
```

#### Moderation

Checks for harmful or inappropriate content.

```typescript
import { Moderation } from "opik/evaluation/metrics";

const metric = new Moderation({
  model: "gpt-4o",
});

const result = await metric.score({
  output: "Here is a helpful response...",
});
// { name: "moderation", value: 1.0 } // 1 = safe content
```

#### Usefulness

Evaluates how useful the response is.

```typescript
import { Usefulness } from "opik/evaluation/metrics";

const metric = new Usefulness({
  model: "gpt-4o",
});

const result = await metric.score({
  input: "How do I sort a list in Python?",
  output: "Use sorted(list) or list.sort() method.",
});
// { name: "usefulness", value: 0.9, reason: "..." }
```

## Creating Custom Metrics

### Heuristic Metric Template

```typescript
import { BaseMetric, EvaluationScoreResult } from "opik/evaluation/metrics";
import { z } from "zod";

export class MyCustomMetric extends BaseMetric {
  public readonly validationSchema = z.object({
    output: z.string(),
    expected: z.string(),
    // Add more fields as needed
  });

  constructor() {
    super("my_custom_metric", true); // name, trackMetric
  }

  score(input: unknown): EvaluationScoreResult {
    const validated = this.validationSchema.parse(input);
    const { output, expected } = validated;

    // Implement your scoring logic
    const value = this.calculateScore(output, expected);

    return {
      name: this.name,
      value,
      reason: `Score: ${value}`,
    };
  }

  private calculateScore(output: string, expected: string): number {
    // Your scoring logic here
    return output.includes(expected) ? 1.0 : 0.0;
  }
}
```

### LLM Judge Metric Template

```typescript
import { BaseLLMJudgeMetric } from "opik/evaluation/metrics";
import { z } from "zod";

export class MyLLMMetric extends BaseLLMJudgeMetric {
  public readonly validationSchema = z.object({
    input: z.string(),
    output: z.string(),
  });

  constructor(options?: { model?: string }) {
    super("my_llm_metric", options?.model ?? "gpt-4o");
  }

  protected getPromptTemplate(): string {
    return `
      Evaluate the following response.
      
      Question: {input}
      Response: {output}
      
      Rate the response from 0 to 1.
      Return JSON: {"score": <number>, "reason": "<explanation>"}
    `;
  }

  protected parseResponse(response: string): EvaluationScoreResult {
    const parsed = JSON.parse(response);
    return {
      name: this.name,
      value: parsed.score,
      reason: parsed.reason,
    };
  }
}
```

### Multi-Score Metric

Metrics can return multiple scores:

```typescript
score(input: unknown): EvaluationScoreResult[] {
  const validated = this.validationSchema.parse(input);
  
  return [
    { name: "accuracy", value: 0.9 },
    { name: "completeness", value: 0.8 },
    { name: "clarity", value: 0.95 },
  ];
}
```

## Model Abstraction

### OpikBaseModel

**Location**: `src/opik/evaluation/models/OpikBaseModel.ts`

The SDK provides a model abstraction layer for LLM interactions:

```typescript
export abstract class OpikBaseModel {
  abstract readonly modelName: string;

  abstract generateProviderResponse(
    messages: OpikMessage[],
    options?: { temperature?: number; seed?: number }
  ): Promise<unknown>;
}
```

### Supported Models

```typescript
type SupportedModelId =
  // OpenAI models (e.g. "gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-4", "o1","o3-mini", etc.)
  // Anthropic Claude models (e.g. "claude-3-5-sonnet", "claude-3-5-sonnet-latest", "claude-3-opus")
  // Google Gemini (e.g. "gemini-1.5-pro", "gemini-1.5-flash", "gemini-2.0-flash")
  string;
```

### Model Resolution

```typescript
import { resolveModel } from "opik/evaluation/models";

// From string ID
const model1 = resolveModel("gpt-4o");

// From LanguageModel instance (Vercel AI SDK)
import { openai } from "@ai-sdk/openai";
const model2 = resolveModel(openai("gpt-4o"));

// From OpikBaseModel instance
const model3 = resolveModel(new OpenAIModel("gpt-4o"));

// Default (undefined → gpt-4o)
const model4 = resolveModel(undefined);
```

## Scoring Key Mapping

### Purpose

Maps dataset/task keys to metric input keys when they don't match.

### Usage

```typescript
const result = await evaluate({
  dataset,
  task: async (item) => ({
    response: await llm.generate(item.question),  // Returns "response"
  }),
  scoringMetrics: [new Contains()],  // Expects "output" and "substring"
  scoringKeyMapping: {
    output: "response",        // Map "output" ← "response"
    substring: "answer",        // Map "substring" ← dataset's "answer"
  },
});
```

### Mapping Flow

```
Dataset Item          Task Output           Metric Input
┌─────────────┐      ┌────────────┐        ┌────────────┐
│ question    │ ──▶  │            │        │            │
│ answer      │      │ response   │ ──▶    │ output     │
│ context     │      │            │        │ expected   │
└─────────────┘      └────────────┘        └────────────┘
                           │                     ▲
                           │    scoringKeyMapping│
                           └─────────────────────┘
                           output ← response
                           expected ← answer
```

### Type Definition

```typescript
type ScoringKeyMappingType = {
  [metricKey: string]: string;  // metricKey ← sourceKey
};
```
