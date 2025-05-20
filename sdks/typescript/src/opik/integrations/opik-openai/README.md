# Opik OpenAI Integration

[![npm version](https://img.shields.io/npm/v/opik-openai.svg)](https://www.npmjs.com/package/opik-openai)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://github.com/comet-ml/opik/blob/main/LICENSE)

Seamlessly integrate [Opik](https://www.comet.com/docs/opik/) observability with your [OpenAI](https://platform.openai.com/docs) applications to trace, monitor, and debug your LLM API calls.

## Features

- 🔍 **Comprehensive Tracing**: Automatically trace OpenAI API calls and completions
- 📊 **Hierarchical Visualization**: View your OpenAI execution as a structured trace with parent-child relationships
- 📝 **Detailed Metadata Capture**: Record model names, prompts, completions, token usage, and custom metadata
- 🚨 **Error Handling**: Capture and visualize errors in your OpenAI API interactions
- 🏷️ **Custom Tagging**: Add custom tags to organize and filter your traces
- 🔄 **Streaming Support**: Full support for streamed completions and chat responses

## Installation

```bash
# npm
npm install opik-openai

# yarn
yarn add opik-openai

# pnpm
pnpm add opik-openai
```

### Requirements

- Node.js ≥ 18
- OpenAI SDK (`openai` ≥ 4.0.0)
- Opik SDK (automatically installed as a dependency)

## Usage

```typescript
import OpenAI from "openai";
import { trackOpenAI } from "opik-openai";

// Initialize the OpenAI client
const openai = new OpenAI({
  apiKey: process.env.OPENAI_API_KEY,
});

// Wrap the client with Opik tracking
const trackedOpenAI = trackOpenAI(openai, {
  // Optional configuration
  traceMetadata: {
    tags: ["production", "my-app"],
  },
});

// Use the tracked client just like the original
async function main() {
  const completion = await trackedOpenAI.chat.completions.create({
    model: "gpt-4",
    messages: [{ role: "user", content: "Hello world" }],
  });

  console.log(completion.choices[0].message);

  // Flush traces at the end of your application
  await trackedOpenAI.flush();
}

main().catch(console.error);
```

## Viewing Traces

To view your traces:

1. Sign in to your [Comet account](https://www.comet.com/signin)
2. Navigate to the Opik section
3. Select your project to view all traces
4. Click on a specific trace to see the detailed execution flow

## Learn More

- [Opik Documentation](https://www.comet.com/docs/opik/)
- [OpenAI Documentation](https://platform.openai.com/docs)
- [Opik TypeScript SDK](https://github.com/comet-ml/opik/tree/main/sdks/typescript)

## License

Apache 2.0
