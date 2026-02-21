# Opik OpenRouter Integration

[![npm version](https://img.shields.io/npm/v/opik-openrouter.svg)](https://www.npmjs.com/package/opik-openrouter)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://github.com/comet-ml/opik/blob/main/LICENSE)

Seamlessly integrate [Opik](https://www.comet.com/docs/opik/) observability with your [OpenRouter](https://openrouter.ai/) applications.

## Features

- 🔍 **Dedicated OpenRouter Integration**
- 🎯 **Explicit source attribution**
- 📊 **Hierarchical tracing** for OpenRouter chat calls
- 🧩 **OpenAI SDK-compatible behavior** through OpenRouter's compatible API contract

## Installation

```bash
# npm
npm install opik-openrouter opik-openai @openrouter/sdk

# yarn
yarn add opik-openrouter opik-openai @openrouter/sdk

# pnpm
pnpm add opik-openrouter opik-openai @openrouter/sdk
```

### Requirements

- Node.js ≥ 18
- OpenRouter SDK (`@openrouter/sdk`)
- Opik SDK (installed automatically by `opik-openrouter` peer dependency)

## Usage

```typescript
import { OpenRouter } from "@openrouter/sdk";
import { trackOpenRouter } from "opik-openrouter";

const openRouter = new OpenRouter({
  apiKey: process.env.OPENROUTER_API_KEY,
});

const trackedOpenRouter = trackOpenRouter(openRouter, {
  traceMetadata: {
    tags: ["production", "router"],
  },
});

const response = await trackedOpenRouter.chat.send({
  model: "openai/gpt-5-mini",
  messages: [{ role: "user", content: "Hello world" }],
});

await trackedOpenRouter.flush();
```

## Viewing Traces

To view your traces:

1. Sign in to your [Comet account](https://www.comet.com/signin)
2. Navigate to the Opik section
3. Select your project to view all traces
4. Click on a specific trace to inspect execution details

## Learn More

- [Opik Documentation](https://www.comet.com/docs/opik/)
- [OpenRouter Documentation](https://openrouter.ai/docs)
- [OpenRouter TypeScript SDK](https://openrouter.ai/docs/sdks/typescript/overview)

## License

Apache 2.0
