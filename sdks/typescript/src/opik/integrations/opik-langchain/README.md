# Opik LangChain Integration

[![npm version](https://img.shields.io/npm/v/opik-langchain.svg)](https://www.npmjs.com/package/opik-langchain)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://github.com/comet-ml/opik/blob/main/LICENSE)

Seamlessly integrate [Opik](https://www.comet.com/docs/opik/) observability with your [LangChain](https://js.langchain.com/) applications to trace, monitor, and debug your LLM chains, agents, and tools.

## Features

- 🔍 **Comprehensive Tracing**: Automatically trace LLM calls, chains, tools, retrievers, and agents
- 📊 **Hierarchical Visualization**: View your LangChain execution as a structured trace with parent-child relationships
- 📝 **Detailed Metadata Capture**: Record model names, prompts, completions, usage statistics, and custom metadata
- 🚨 **Error Handling**: Capture and visualize errors at every step of your LangChain execution
- 🏷️ **Custom Tagging**: Add custom tags to organize and filter your traces

## Installation

```bash
# npm
npm install opik-langchain

# yarn
yarn add opik-langchain

# pnpm
pnpm add opik-langchain
```

### Requirements

- Node.js ≥ 18
- LangChain (`@langchain/core` ≥ 0.3.78)
- Opik SDK (`opik` peer dependency)

## Quick Start

```typescript
import { OpikCallbackHandler } from "opik-langchain";
import { ChatOpenAI } from "@langchain/openai";

// Create the Opik callback handler
const opikHandler = new OpikCallbackHandler();

// Create your LangChain components with the handler
const llm = new ChatOpenAI({
  callbacks: [opikHandler],
});

// Run LLM
const response = await llm.invoke("Hello, how can you help me today?", {
  callbacks: [opikHandler],
});

// Optionally, ensure all traces are sent before your app terminates
await opikHandler.flushAsync();
```

## Advanced Configuration

The `OpikCallbackHandler` constructor accepts the following options:

```typescript
interface OpikCallbackHandlerOptions {
  // Optional array of tags to apply to all traces
  tags?: string[];

  // Optional metadata to include with all traces
  metadata?: Record<string, unknown>;

  // Optional project name for Opik
  projectName?: string;

  // Optional pre-configured Opik client
  client?: Opik;
}
```

### Capturing Custom Metadata

You can pass custom metadata when invoking your chains:

```typescript
const response = await chain.invoke(
  { input: "Tell me about AI" },
  {
    callbacks: [opikHandler],
    metadata: {
      userId: "user-123",
      sessionId: "session-456",
      requestId: "req-789",
    },
  }
);
```

## Viewing Traces

To view your traces:

1. Sign in to your [Comet account](https://www.comet.com/signin)
2. Navigate to the Opik section
3. Select your project to view all traces
4. Click on a specific trace to see the detailed execution flow

## Learn More

- [Opik Documentation](https://www.comet.com/docs/opik/)
- [LangChain Documentation](https://js.langchain.com/)
- [Opik TypeScript SDK](https://github.com/comet-ml/opik/tree/main/sdks/typescript)

## License

Apache 2.0
