import type { RunnableConfig } from "@langchain/core/runnables";
import type { Opik, OpikConfig } from "opik";
import type { OpikCallbackHandler } from "opik-langchain";

export interface TrackLangGraphOptions {
  callbackHandler?: OpikCallbackHandler;
  tags?: string[];
  metadata?: Record<string, unknown>;
  projectName?: string;
  client?: Opik;
  clientConfig?: OpikConfig;
  threadId?: string;
  includeGraphDefinition?: boolean;
  xray?: boolean | number;
}

export interface OpikLangGraphExtension {
  flush(): Promise<void>;
  opikCallbackHandler: OpikCallbackHandler;
}

export interface LangGraphDrawable {
  drawMermaid?: () => string;
  toJSON?: () => Record<string, unknown>;
}

export interface TrackableLangGraph {
  config?: RunnableConfig;
  getGraph?: (
    config?: RunnableConfig & { xray?: boolean | number }
  ) => LangGraphDrawable;
}
