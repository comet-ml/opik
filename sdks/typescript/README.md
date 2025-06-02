<h1 align="center" style="border-bottom: none">
    <div>
        <a href="https://www.comet.com/site/products/opik/?from=llm&utm_source=opik&utm_medium=github&utm_content=header_img&utm_campaign=opik"><picture>
            <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/logo-dark-mode.svg">
            <source media="(prefers-color-scheme: light)" srcset="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/opik-logo.svg">
            <img alt="Comet Opik logo" src="https://raw.githubusercontent.com/comet-ml/opik/refs/heads/main/apps/opik-documentation/documentation/static/img/opik-logo.svg" width="200" />
        </picture></a>
        <br>
        Opik TypeScript SDK
    </div>
</h1>
<h2 align="center" style="border-bottom: none">Open-source LLM evaluation platform</h2>

<p align="center">
  <a href="https://www.npmjs.com/package/opik"><img src="https://img.shields.io/npm/v/opik.svg" alt="NPM Version"></a>
  <a href="https://www.npmjs.com/package/opik"><img src="https://img.shields.io/npm/dt/opik.svg" alt="NPM Downloads"></a>
  <a href="https://github.com/comet-ml/opik/blob/main/LICENSE"><img src="https://img.shields.io/github/license/comet-ml/opik" alt="License"></a>
</p>

The Opik TypeScript SDK allows you to integrate your TypeScript and JavaScript applications with the Opik platform, enabling comprehensive tracing, evaluation, and monitoring of your LLM systems. Opik helps you build, evaluate, and optimize LLM systems that run better, faster, and cheaper.

Opik is an open-source LLM evaluation platform by [Comet](https://www.comet.com?from=llm&utm_source=opik&utm_medium=github&utm_content=ts_sdk_readme&utm_campaign=opik). For more information about the broader Opik ecosystem, visit our main [GitHub repository](https://github.com/comet-ml/opik), [Website](https://www.comet.com/site/products/opik/), or [Documentation](https://www.comet.com/docs/opik/).

## Installation

You can install the `opik` package using your favorite package manager.

```bash
npm install opik
```

## Opik Configuration

You can configure the Opik client using environment variables.

`.env` file:

```bash
OPIK_API_KEY="your-api-key"
OPIK_URL_OVERRIDE="https://www.comet.com/opik/api"
OPIK_PROJECT_NAME="your-project-name"
OPIK_WORKSPACE_NAME="your-workspace-name"
```

Or you can pass the configuration to the Opik client constructor.

```typescript
import { Opik } from "opik";

const client = new Opik({
  apiKey: "<your-api-key>",
  apiUrl: "https://www.comet.com/opik/api",
  projectName: "<your-project-name>",
  workspaceName: "<your-workspace-name>",
});
```

## Usage

```typescript
import { Opik } from "opik";

// Create a new Opik client with your configuration
const client = new Opik();

// Log 10 traces
for (let i = 0; i < 10; i++) {
  const someTrace = client.trace({
    name: `Trace ${i}`,
    input: {
      prompt: `Hello, world! ${i}`,
    },
    output: {
      response: `Hello, world! ${i}`,
    },
  });

  // For each trace, log 10 spans
  for (let j = 0; j < 10; j++) {
    const someSpan = someTrace.span({
      name: `Span ${i}-${j}`,
      type: "llm",
      input: {
        prompt: `Hello, world! ${i}:${j}`,
      },
      output: {
        response: `Hello, world! ${i}:${j}`,
      },
    });

    // Some LLM work
    await new Promise((resolve) => setTimeout(resolve, 100));

    // Mark the span as ended
    someSpan.end();
  }

  // Mark the trace as ended
  someTrace.end();
}

// Flush the client to send all traces and spans
await client.flush();
```

## Vercel AI SDK Integration

Opik provides seamless integration with the Vercel AI SDK through OpenTelemetry instrumentation.

### Installation

Install the required dependencies:

```bash
npm install opik ai @opentelemetry/sdk-node @opentelemetry/auto-instrumentations-node
```

### Usage

```typescript
import { openai } from "@ai-sdk/openai";
import { getNodeAutoInstrumentations } from "@opentelemetry/auto-instrumentations-node";
import { NodeSDK } from "@opentelemetry/sdk-node";
import { generateText } from "ai";
import { OpikExporter } from "opik/vercel";

const sdk = new NodeSDK({
  traceExporter: new OpikExporter(),
  instrumentations: [getNodeAutoInstrumentations()],
});

sdk.start();

const { text } = await generateText({
  model: openai("gpt-4o-mini"),
  prompt: "What is love? Describe it in 10 words or less.",
  experimental_telemetry: OpikExporter.getSettings({
    name: "ai-sdk-integration",
  }),
});

await sdk.shutdown();
```

This integration automatically captures:

- Input prompts and messages
- Model responses
- Token usage statistics
- Tool calls and their results
- Timing information
- Error states

All this telemetry data is automatically sent to your Opik project for analysis and monitoring.

## Contributing

Contributions are welcome! If you have any suggestions or improvements, please feel free to open an [issue](https://github.com/comet-ml/opik/issues) or submit a [pull request](https://github.com/comet-ml/opik/pulls).

## License

This project is licensed under the [Apache License 2.0](https://github.com/comet-ml/opik/blob/main/LICENSE).
