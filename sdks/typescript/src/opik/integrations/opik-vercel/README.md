# Opik Vercel AI SDK Integration

[![npm version](https://img.shields.io/npm/v/opik-vercel.svg)](https://www.npmjs.com/package/opik-vercel)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://github.com/comet-ml/opik/blob/main/LICENSE)

Seamlessly integrate [Opik](https://www.comet.com/docs/opik/) observability with your [Vercel AI SDK](https://sdk.vercel.ai/docs) applications to trace, monitor, and debug your AI workflows.

## Features

- 🔍 **Comprehensive Tracing**: Automatically trace AI SDK calls and completions
- 📊 **Hierarchical Visualization**: View your AI execution as a structured trace with parent-child relationships
- 📝 **Detailed Metadata Capture**: Record model names, prompts, completions, token usage, and custom metadata
- 🚨 **Error Handling**: Capture and visualize errors in your AI API interactions
- 🏷️ **Custom Tagging**: Add custom tags to organize and filter your traces
- 🔄 **Streaming Support**: Full support for streamed completions and chat responses

## Installation

```bash
# npm
npm install opik-vercel

# yarn
yarn add opik-vercel

# pnpm
pnpm add opik-vercel
```

### Requirements

- Node.js ≥ 18
- Vercel AI SDK (`ai` ≥ 3.0.0)
- Opik SDK (automatically installed as a peer dependency)
- OpenTelemetry packages (automatically installed as peer dependencies)

## Usage

```typescript
import { openai } from "@ai-sdk/openai";
import { generateText } from "ai";
import { OpikExporter } from "opik-vercel";

// Initialize Opik exporter
const exporter = new OpikExporter({
  tags: ["production", "my-app"],
  metadata: {
    environment: "production",
  },
});

// Use with Vercel AI SDK
async function main() {
  const result = await generateText({
    model: openai("gpt-4"),
    prompt: "What is the capital of France?",
    experimental_telemetry: OpikExporter.getSettings({
      isEnabled: true,
      recordInputs: true,
      recordOutputs: true,
      name: "capital-question",
    }),
  });

  console.log(result.text);

  // Flush traces at the end of your application
  await exporter.shutdown();
}

main().catch(console.error);
```

## Configuration

### OpikExporter Options

```typescript
const exporter = new OpikExporter({
  client?: Opik,           // Optional: Custom Opik client instance
  tags?: string[],         // Optional: Tags to apply to all traces
  metadata?: Record<string, unknown>  // Optional: Metadata to apply to all traces
});
```

### Telemetry Settings

Use `OpikExporter.getSettings()` to configure telemetry for individual AI SDK calls:

```typescript
OpikExporter.getSettings({
  isEnabled: true, // Enable/disable telemetry
  recordInputs: true, // Record input data
  recordOutputs: true, // Record output data
  name: "trace-name", // Custom trace name
  functionId: "func-123", // Function identifier
  metadata: {
    // Additional metadata
    version: "1.0",
  },
});
```

## Viewing Traces

To view your traces:

1. Sign in to your [Comet account](https://www.comet.com/signin)
2. Navigate to the Opik section
3. Select your project to view all traces
4. Click on a specific trace to see the detailed execution flow

## Learn More

- [Opik Documentation](https://www.comet.com/docs/opik/)
- [Vercel AI SDK Documentation](https://sdk.vercel.ai/docs)
- [Opik TypeScript SDK](https://github.com/comet-ml/opik/tree/main/sdks/typescript)

## License

Apache 2.0
