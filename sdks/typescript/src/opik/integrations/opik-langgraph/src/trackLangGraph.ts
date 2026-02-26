import type { RunnableConfig } from "@langchain/core/runnables";
import { OpikCallbackHandler } from "opik-langchain";
import type {
  OpikLangGraphExtension,
  TrackLangGraphOptions,
  TrackableLangGraph,
} from "./types";

const OPIK_GRAPH_DEFINITION_KEY = "_opik_graph_definition";
const OPIK_LANGGRAPH_TRACKED = Symbol.for("opik.langgraph.tracked");

type MutableTrackedGraph = TrackableLangGraph & {
  [OPIK_LANGGRAPH_TRACKED]?: true;
  flush?: () => Promise<void>;
  opikCallbackHandler?: OpikCallbackHandler;
};

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;

const isOpikCallbackHandler = (
  value: unknown
): value is OpikCallbackHandler =>
  isRecord(value) &&
  value.name === "OpikCallbackHandler" &&
  typeof value.flushAsync === "function";

const findExistingOpikHandler = (
  callbacks: RunnableConfig["callbacks"]
): OpikCallbackHandler | undefined => {
  if (!callbacks) {
    return undefined;
  }

  if (Array.isArray(callbacks)) {
    return callbacks.find(isOpikCallbackHandler);
  }

  if (isRecord(callbacks) && Array.isArray(callbacks.handlers)) {
    return callbacks.handlers.find(isOpikCallbackHandler);
  }

  return undefined;
};

const attachCallbackHandler = (
  config: RunnableConfig,
  callbackHandler: OpikCallbackHandler
): OpikCallbackHandler => {
  const existingHandler = findExistingOpikHandler(config.callbacks);
  if (existingHandler) {
    return existingHandler;
  }

  if (Array.isArray(config.callbacks)) {
    config.callbacks = [...config.callbacks, callbackHandler];
    return callbackHandler;
  }

  if (
    isRecord(config.callbacks) &&
    typeof config.callbacks.addHandler === "function"
  ) {
    config.callbacks.addHandler(callbackHandler);
    return callbackHandler;
  }

  config.callbacks = [callbackHandler];
  return callbackHandler;
};

const getGraphDefinition = (
  graph: TrackableLangGraph,
  xray: boolean | number
): { format: "mermaid"; data: string } | undefined => {
  if (typeof graph.getGraph !== "function") {
    return undefined;
  }

  try {
    const drawableGraph = graph.getGraph({ xray });
    if (!drawableGraph || typeof drawableGraph.drawMermaid !== "function") {
      return undefined;
    }

    return {
      format: "mermaid",
      data: drawableGraph.drawMermaid(),
    };
  } catch {
    return undefined;
  }
};

const normalizeOptions = (
  options?: TrackLangGraphOptions | OpikCallbackHandler
): Required<Pick<TrackLangGraphOptions, "includeGraphDefinition" | "xray">> &
  TrackLangGraphOptions => {
  if (isOpikCallbackHandler(options)) {
    return {
      callbackHandler: options,
      includeGraphDefinition: true,
      xray: true,
    };
  }

  return {
    ...options,
    includeGraphDefinition: options?.includeGraphDefinition ?? true,
    xray: options?.xray ?? true,
  };
};

const createCallbackHandler = (
  options: TrackLangGraphOptions
): OpikCallbackHandler => {
  if (options.callbackHandler) {
    return options.callbackHandler;
  }

  return new OpikCallbackHandler({
    tags: options.tags as [],
    metadata: options.metadata,
    projectName: options.projectName,
    client: options.client,
    clientConfig: options.clientConfig,
    threadId: options.threadId,
  });
};

/**
 * Injects Opik tracing into a compiled LangGraph graph.
 *
 * The graph is modified in place by attaching an `OpikCallbackHandler` to
 * `graph.config.callbacks`, so all future invocations are tracked automatically.
 */
export const trackLangGraph = <GraphType extends TrackableLangGraph>(
  graph: GraphType,
  options?: TrackLangGraphOptions | OpikCallbackHandler
): GraphType & OpikLangGraphExtension => {
  const trackedGraph = graph as GraphType & OpikLangGraphExtension & MutableTrackedGraph;

  if (trackedGraph[OPIK_LANGGRAPH_TRACKED]) {
    return trackedGraph;
  }

  const normalizedOptions = normalizeOptions(options);

  const config: RunnableConfig = {
    ...(graph.config ?? {}),
  };

  const callbackHandler =
    findExistingOpikHandler(config.callbacks) ??
    createCallbackHandler(normalizedOptions);
  attachCallbackHandler(config, callbackHandler);

  if (normalizedOptions.includeGraphDefinition) {
    const graphDefinition = getGraphDefinition(graph, normalizedOptions.xray);

    if (graphDefinition) {
      config.metadata = {
        ...(config.metadata ?? {}),
        [OPIK_GRAPH_DEFINITION_KEY]: graphDefinition,
      };
    }
  }

  trackedGraph.config = config;

  if (typeof trackedGraph.flush !== "function") {
    Object.defineProperty(trackedGraph, "flush", {
      value: callbackHandler.flushAsync.bind(callbackHandler),
      writable: true,
      configurable: true,
    });
  }

  Object.defineProperty(trackedGraph, "opikCallbackHandler", {
    value: callbackHandler,
    writable: false,
    configurable: true,
  });

  trackedGraph[OPIK_LANGGRAPH_TRACKED] = true;

  return trackedGraph;
};
