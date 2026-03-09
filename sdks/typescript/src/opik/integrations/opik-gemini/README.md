# Opik Gemini Integration

[![npm version](https://img.shields.io/npm/v/opik-gemini.svg)](https://www.npmjs.com/package/opik-gemini)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://github.com/comet-ml/opik/blob/main/LICENSE)

Seamlessly integrate [Opik](https://www.comet.com/docs/opik/) observability with your [Google Gemini](https://ai.google.dev/) applications.

## Features

- 🔍 **Comprehensive Tracing**: Automatically trace Gemini API calls
- 📊 **Hierarchical Visualization**: View execution as structured traces and spans
- 📝 **Detailed Metadata**: Record model names, prompts, completions, token usage
- 🚨 **Error Handling**: Capture and visualize errors with full context
- 🏷️ **Custom Tagging**: Add custom tags and metadata to organize traces
- 🔄 **Streaming Support**: Full support for streamed responses
- ⚡ **Non-blocking**: Minimal performance impact with async batching
- 🎯 **Type-Safe**: Full TypeScript support with comprehensive types

## Installation

```bash
npm install opik-gemini @google/genai
```

### Requirements

- Node.js ≥ 18
- @google/genai SDK (≥ 1.0.0)
- Opik SDK (automatically installed as peer dependency)

**Note**: The official Google GenAI SDK package is `@google/genai` (not `@google/generative-ai`). This is Google Deepmind's unified SDK for both Gemini Developer API and Vertex AI.

## Quick Start

### Basic Usage

```typescript
import { GoogleGenAI } from "@google/genai";
import { trackGemini } from "opik-gemini";

// Initialize Gemini client
const genAI = new GoogleGenAI({ apiKey: process.env.GEMINI_API_KEY });

// Wrap with Opik tracking
const trackedGenAI = trackGemini(genAI, {
  traceMetadata: {
    tags: ["production", "my-app"],
  },
});

// Use normally - all calls are automatically tracked
async function main() {
  const response = await trackedGenAI.models.generateContent({
    model: "gemini-2.0-flash-001",
    contents: "What is the capital of France?",
  });

  console.log(response.text);

  // Ensure all traces are sent before exit
  await trackedGenAI.flush();
}

main();
```

### Streaming Support

```typescript
import { GoogleGenAI } from "@google/genai";
import { trackGemini } from "opik-gemini";

const genAI = new GoogleGenAI({ apiKey: process.env.GEMINI_API_KEY });
const trackedGenAI = trackGemini(genAI);

async function streamExample() {
  const response = await trackedGenAI.models.generateContentStream({
    model: "gemini-2.0-flash-001",
    contents: "Write a haiku about AI",
  });

  // Stream is automatically tracked
  let streamedContent = "";
  for await (const chunk of response) {
    const chunkText = chunk.text;
    if (chunkText) {
      process.stdout.write(chunkText);
      streamedContent += chunkText;
    }
  }

  console.log("\n");
  await trackedGenAI.flush();
}

streamExample();
```

### Using with Existing Opik Client

```typescript
import { Opik } from "opik";
import { GoogleGenAI } from "@google/genai";
import { trackGemini } from "opik-gemini";

const opikClient = new Opik({
  projectName: "gemini-project",
});

const genAI = new GoogleGenAI({ apiKey: process.env.GEMINI_API_KEY });
const trackedGenAI = trackGemini(genAI, {
  client: opikClient,
  traceMetadata: {
    tags: ["gemini", "production"],
    environment: "prod",
  },
});

// All calls will be logged to "gemini-project"
const response = await trackedGenAI.models.generateContent({
  model: "gemini-2.0-flash-001",
  contents: "Hello, Gemini!",
});

console.log(response.text);
```

### Custom Generation Names

```typescript
import { trackGemini } from "opik-gemini";

const trackedGenAI = trackGemini(genAI, {
  generationName: "MyCustomGeminiCall",
});

// Traces will appear as "MyCustomGeminiCall" in Opik
```

### Nested Tracing

```typescript
import { Opik } from "opik";
import { trackGemini } from "opik-gemini";

const opikClient = new Opik();
const trackedGenAI = trackGemini(genAI, { client: opikClient });

async function processQuery(query: string) {
  // Create parent trace
  const trace = opikClient.trace({
    name: "ProcessUserQuery",
    input: { query },
  });

  // Gemini call will be nested under this trace
  const trackedGenAIWithParent = trackGemini(genAI, {
    parent: trace,
    client: opikClient,
  });

  const response = await trackedGenAIWithParent.models.generateContent({
    model: "gemini-2.0-flash-001",
    contents: query,
  });

  trace.update({
    output: { response: response.text },
  });
  trace.end();

  return response;
}
```

## Configuration

### TrackOpikConfig Options

```typescript
interface TrackOpikConfig {
  /** Opik client instance (optional, creates singleton if not provided) */
  client?: Opik;

  /** Custom name for the generation (optional, defaults to method name) */
  generationName?: string;

  /** Parent trace or span for nested tracing (optional) */
  parent?: Trace | Span;

  /** Additional metadata for traces (optional) */
  traceMetadata?: {
    tags?: string[];
    [key: string]: unknown;
  };
}
```

## What Gets Tracked

The integration automatically captures:

- **Input**: Prompt contents and generation config
- **Output**: Generated text, candidates, and safety ratings
- **Model**: Model name/version (e.g., "gemini-pro", "gemini-1.5-flash")
- **Usage**: Token counts (prompt, completion, total)
- **Metadata**: Provider info, model settings, safety settings
- **Errors**: Error messages and stack traces
- **Timing**: Start/end times and duration

## Best Practices

1. **Always call `flush()` before process exit** (especially in short-lived scripts):

   ```typescript
   await trackedGenAI.flush();
   ```

2. **Use descriptive tags** for easier filtering:

   ```typescript
   trackGemini(genAI, {
     traceMetadata: {
       tags: ["production", "customer-support", "v2"],
     },
   });
   ```

3. **Reuse Opik client** across your application for consistency:

   ```typescript
   const opikClient = new Opik({ projectName: "my-project" });
   const trackedGenAI = trackGemini(genAI, { client: opikClient });
   ```

4. **Use nested tracing** for complex workflows to understand call hierarchies.

## Supported Gemini Models

This integration supports all Google Gemini models including:

- `gemini-2.0-flash-001` (Latest, recommended for most use cases)
- `gemini-1.5-pro`
- `gemini-1.5-flash`
- `gemini-pro`
- `gemini-pro-vision`
- Any future Gemini models

Refer to [Google's official documentation](https://ai.google.dev/models/gemini) for the complete list of available models and their capabilities.

## Development

### Building

```bash
npm run build
```

### Type Checking

```bash
npm run typecheck
```

### Testing

```bash
npm test
```

## Examples

Check out the [examples directory](../../../../examples) for more usage examples.

## Documentation

- [Opik Documentation](https://www.comet.com/docs/opik/)
- [Google Gemini Documentation](https://ai.google.dev/)
- [TypeScript SDK Guide](https://www.comet.com/docs/opik/typescript-sdk)

## Support

- [GitHub Issues](https://github.com/comet-ml/opik/issues)
- [Comet Support](mailto:support@comet.com)
- [Community Slack](https://www.comet.com/docs/opik/community/)

## License

Apache-2.0

## Contributing

Contributions are welcome! Please see our [Contributing Guide](https://github.com/comet-ml/opik/blob/main/CONTRIBUTING.md).
