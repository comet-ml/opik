# Opik LangGraph.js Integration

[![npm version](https://img.shields.io/npm/v/opik-langgraph.svg)](https://www.npmjs.com/package/opik-langgraph)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://github.com/comet-ml/opik/blob/main/LICENSE)

Integrate [Opik](https://www.comet.com/docs/opik/) with [LangGraph.js](https://langchain-ai.github.io/langgraphjs/) to automatically trace graph execution, node-level spans, usage, and errors.

## Features

- Automatic callback injection for compiled LangGraph graphs
- No callback wiring required on every `invoke()` / `stream()` call
- Graph definition captured in trace metadata for visualization
- Compatible with existing LangChain callback configuration

## Installation

```bash
# npm
npm install opik-langgraph @langchain/langgraph @langchain/openai

# yarn
yarn add opik-langgraph @langchain/langgraph @langchain/openai

# pnpm
pnpm add opik-langgraph @langchain/langgraph @langchain/openai
```

### Requirements

- Node.js >= 18
- `@langchain/langgraph` (`>=0.3.8 <0.5.0`)
- `opik`

## Quick Start

```typescript
import { Annotation, END, START, StateGraph } from "@langchain/langgraph";
import { ChatOpenAI } from "@langchain/openai";
import { HumanMessage } from "@langchain/core/messages";
import { trackLangGraph } from "opik-langgraph";

const GraphState = Annotation.Root({
  messages: Annotation<HumanMessage[]>({
    reducer: (left, right) => left.concat(right),
  }),
});

const model = new ChatOpenAI({ model: "gpt-4o-mini" });

const graphBuilder = new StateGraph(GraphState)
  .addNode("chat", async (state) => {
    const response = await model.invoke(state.messages);
    return { messages: [response] };
  })
  .addEdge(START, "chat")
  .addEdge("chat", END);

const app = graphBuilder.compile();

const trackedApp = trackLangGraph(app, {
  projectName: "langgraph-js-demo",
  tags: ["langgraph", "typescript"],
});

const result = await trackedApp.invoke({
  messages: [new HumanMessage("How do I use LangGraph.js with Opik?")],
});

console.log(result);
await trackedApp.flush();
```

## Using an Existing Handler

```typescript
import { OpikCallbackHandler, trackLangGraph } from "opik-langgraph";

const opikHandler = new OpikCallbackHandler({
  projectName: "my-project",
  threadId: "conversation-123",
});

const trackedGraph = trackLangGraph(compiledGraph, opikHandler);
await trackedGraph.invoke(input);
await trackedGraph.flush();
```

## API

### `trackLangGraph(graph, options?)`

Adds an Opik callback handler to `graph.config.callbacks` in place and returns the same graph with:

- `flush(): Promise<void>` convenience method
- `opikCallbackHandler` reference

### `TrackLangGraphOptions`

```typescript
interface TrackLangGraphOptions {
  callbackHandler?: OpikCallbackHandler;
  tags?: string[];
  metadata?: Record<string, unknown>;
  projectName?: string;
  client?: Opik;
  clientConfig?: OpikConfig;
  threadId?: string;
  includeGraphDefinition?: boolean; // default: true
  xray?: boolean | number; // default: true
}
```

## License

Apache 2.0
