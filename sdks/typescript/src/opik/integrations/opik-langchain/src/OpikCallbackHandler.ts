import {
  BaseCallbackHandler,
  BaseCallbackHandlerInput,
} from "@langchain/core/callbacks/base";
import { Serialized } from "@langchain/core/load/serializable";
import { ChainValues } from "@langchain/core/utils/types";
import {
  Opik,
  Span,
  Trace,
  logger,
  SpanType,
  OpikSpanType,
  OpikConfig,
} from "opik";
import {
  extractCallArgs,
  inputFromChainValues,
  inputFromMessages,
  outputFromChainValues,
  outputFromGenerations,
  outputFromToolOutput,
  safeParseSerializedJson,
} from "./utils";
import { RunnableConfig } from "@langchain/core/runnables";
import { ChatResult, LLMResult } from "@langchain/core/outputs";
import { BaseMessage } from "@langchain/core/messages";
import { AgentAction, AgentFinish } from "@langchain/core/agents";

type JsonNode = Record<string, unknown>;
export interface OpikCallbackHandlerOptions {
  tags?: [];
  metadata?: JsonNode;
  projectName?: string;
  client?: Opik;
  clientConfig?: OpikConfig;
  threadId?: string;
}

type StartTracingArgs = {
  runId: string;
  parentRunId?: string;
  name: string;
  input: JsonNode;
  tags?: string[];
  metadata?: JsonNode & {
    ls_provider?: string;
    ls_model_name?: string;
  };
  type?: SpanType;
};

type EndTracingArgs = {
  runId: string;
  output?: JsonNode;
  tags?: string[];
  error?: Error;
  usage?: Record<string, number>;
  metadata?: JsonNode;
};

export class OpikCallbackHandler
  extends BaseCallbackHandler
  implements BaseCallbackHandlerInput
{
  name = "OpikCallbackHandler";

  private options: OpikCallbackHandlerOptions;
  private client: Opik;
  private rootTraceId?: string;
  private tracerMap: Map<string, Trace | Span> = new Map();
  private rootTraces: Map<string, Trace> = new Map();

  constructor(options?: Partial<OpikCallbackHandlerOptions>) {
    super();

    this.options = {
      ...options,
    };
    this.client = options?.client ?? new Opik(options?.clientConfig);
    if (options?.threadId) {
      this.options.metadata = {
        ...this.options.metadata,
        threadId: options.threadId,
      };
    }

    if (options?.projectName) {
      this.client.config.projectName = options?.projectName;
    }
  }

  private startTracing({
    runId,
    parentRunId,
    name,
    input,
    tags,
    metadata,
    type,
  }: StartTracingArgs) {
    const provider = metadata?.ls_provider;
    const model = metadata?.ls_model_name;

    if (!parentRunId && runId === this.rootTraceId) {
      return;
    }

    const existingTracer = this.tracerMap.get(runId);
    if (existingTracer) {
      existingTracer.update({
        input: inputFromChainValues(input),
        tags,
        metadata,
        model,
        provider,
      });

      return;
    }

    if (!parentRunId) {
      this.rootTraceId = runId;
      const trace = this.client.trace({
        name,
        input,
        tags: this.options.tags,
        metadata,
        threadId: this.options.metadata?.threadId as string | undefined,
      });

      this.rootTraces.set(runId, trace);

      const span = trace.span({
        type: type || OpikSpanType.General,
        name,
        input,
        tags,
        metadata,
        model,
        provider,
      });

      this.tracerMap.set(runId, span);

      return;
    }

    const parentTracer = this.tracerMap.get(parentRunId);

    if (!parentTracer) {
      logger.info(`Parent ${parentRunId} not found`);
      return;
    }

    const span = parentTracer.span({
      type: type || OpikSpanType.General,
      name,
      input: inputFromChainValues(input),
      tags,
      metadata,
      model,
      provider,
    });

    this.tracerMap.set(runId, span);
  }

  private endTracing({
    runId,
    output,
    error,
    tags,
    usage,
    metadata,
  }: EndTracingArgs) {
    let errorInfo;
    if (error) {
      logger.debug(`End tracing because of error ${error.message}`);
      errorInfo = {
        message: error.message,
        exceptionType: error.name,
        traceback: error.stack ?? "",
      };
    }

    const span = this.tracerMap.get(runId);

    if (!span) {
      logger.debug(`handleChainEnd span ${runId} has not found`);

      return;
    }

    span.update({
      output,
      errorInfo,
      tags,
      usage,
      metadata,
      endTime: new Date(),
    });

    if (runId === this.rootTraceId) {
      const rootTrace = this.rootTraces.get(this.rootTraceId);
      if (rootTrace) {
        rootTrace.update({
          output,
          errorInfo,
          endTime: new Date(),
        });
      }
      this.rootTraceId = undefined;
      this.tracerMap.clear();
      this.rootTraces.clear();
    }
  }

  async handleChatModelStart(
    llm: Serialized,
    messages: BaseMessage[][],
    runId: string,
    parentRunId?: string,
    extraParams?: {
      options: RunnableConfig;
      invocation_params?: Record<string, unknown>;
      batch_size: number;
      cache?: boolean;
    },
    tags?: string[],
    metadata?: Record<string, unknown>,
    runName?: string
  ): Promise<void> {
    logger.debug(
      `handleChatModelStart runId - ${runId}, parentRunId ${parentRunId}`
    );
    this.startTracing({
      runId,
      parentRunId,
      name: runName ?? llm.id.at(-1)?.toString() ?? "Chat Model",
      type: OpikSpanType.Llm,
      input: inputFromMessages(messages),
      tags,
      metadata: {
        ...metadata,
        ...extractCallArgs(llm, extraParams?.invocation_params || {}, metadata),
        tools: extraParams?.invocation_params?.tools,
      },
    });
  }

  async handleLLMStart(
    llm: Serialized,
    prompts: string[],
    runId: string,
    parentRunId?: string,
    extraParams?: {
      options: RunnableConfig;
      invocation_params?: Record<string, unknown>;
      batch_size: number;
      cache?: boolean;
    },
    tags?: string[],
    metadata?: Record<string, unknown>,
    runName?: string
  ): Promise<void> {
    logger.debug(`handleLLMStart runId - ${runId}, parentRunId ${parentRunId}`);

    this.startTracing({
      runId,
      parentRunId,
      name: runName ?? llm.id.at(-1)?.toString() ?? "LLM",
      type: OpikSpanType.Llm,
      input: { prompts },
      tags,
      metadata: {
        ...metadata,
        ...extractCallArgs(llm, extraParams?.invocation_params || {}, metadata),
      },
    });
  }

  async handleLLMError(
    error: Error,
    runId: string,
    parentRunId?: string,
    tags?: string[]
  ): Promise<void> {
    logger.debug(`handleLLMError runId - ${runId}, parentRunId ${parentRunId}`);
    this.endTracing({
      runId,
      error,
      tags,
    });
  }

  async handleLLMEnd(
    output: LLMResult | ChatResult,
    runId: string,
    parentRunId?: string,
    tags?: string[]
  ): Promise<void> {
    logger.debug(`handleLLMEnd runId - ${runId}, parentRunId ${parentRunId}`);
    const { llmOutput, generations, ...metadata } = output;

    const tokenUsage =
      llmOutput?.tokenUsage || llmOutput?.estimatedTokens || {};

    this.endTracing({
      runId,
      output: outputFromGenerations(generations),
      usage: {
        prompt_tokens: tokenUsage.promptTokens,
        completion_tokens: tokenUsage.completionTokens,
        total_tokens: tokenUsage.totalTokens,
      },
      tags,
      metadata,
    });
  }

  async handleChainStart(
    chain: Serialized,
    input: ChainValues,
    runId: string,
    parentRunId?: string,
    tags?: string[],
    metadata?: Record<string, unknown>,
    runType?: string,
    runName?: string
  ): Promise<void> {
    logger.debug(
      `handleChainStart runId - ${runId}, parentRunId ${parentRunId}`
    );
    if (tags?.includes("langsmith:hidden")) {
      return;
    }
    const name = runName ?? chain.id.at(-1)?.toString() ?? "Chain";

    const tracingData: StartTracingArgs = {
      runId,
      parentRunId,
      name,
      input: inputFromChainValues(input),
      tags: this.options.tags,
      metadata: {
        ...metadata,
        ...extractCallArgs(chain, {}, metadata),
      },
    };

    if (!parentRunId) {
      tracingData.metadata = {
        ...metadata,
        ...this.options.metadata,
      };
    }

    this.startTracing(tracingData);
  }

  async handleChainError(
    error: Error,
    runId: string,
    parentRunId?: string,
    tags?: string[]
  ): Promise<void> {
    logger.debug(
      `handleChainError runId - ${runId}, parentRunId ${parentRunId}`
    );
    this.endTracing({
      runId,
      tags,
      error,
    });
  }

  async handleChainEnd(
    output: ChainValues,
    runId: string,
    parentRunId?: string,
    tags?: string[]
  ): Promise<void> {
    logger.debug(`handleChainEnd runId - ${runId}, parentRunId ${parentRunId}`);
    this.endTracing({
      runId,
      output: outputFromChainValues(output),
      tags,
    });
  }

  async handleToolStart(
    tool: Serialized,
    input: string,
    runId: string,
    parentRunId?: string,
    tags?: string[],
    metadata?: Record<string, unknown>,
    runName?: string
  ): Promise<void> {
    logger.debug(
      `handleToolStart runId - ${runId}, parentRunId ${parentRunId}`
    );
    this.startTracing({
      runId,
      parentRunId,
      name: runName ?? tool.id.at(-1)?.toString() ?? "Tool",
      input: safeParseSerializedJson(input),
      tags,
      type: OpikSpanType.Tool,
      metadata: {
        ...metadata,
        ...extractCallArgs(tool, {}, metadata),
      },
    });
  }

  async handleToolError(
    error: Error,
    runId: string,
    parentRunId?: string,
    tags?: string[]
  ): Promise<void> {
    logger.debug(
      `handleToolError runId - ${runId}, parentRunId ${parentRunId}`
    );
    this.endTracing({
      runId,
      tags,
      error,
    });
  }

  async handleToolEnd(
    output: unknown,
    runId: string,
    parentRunId?: string,
    tags?: string[]
  ): Promise<void> {
    logger.debug(`handleToolEnd runId - ${runId}, parentRunId ${parentRunId}`);
    this.endTracing({
      runId,
      output: outputFromToolOutput(output),
      tags,
    });
  }

  async handleAgentAction(
    action: AgentAction,
    runId: string,
    parentRunId?: string,
    tags?: string[]
  ): Promise<void> {
    logger.debug(
      `handleAgentAction runId - ${runId}, parentRunId ${parentRunId}`
    );
    this.startTracing({
      runId,
      parentRunId,
      name: action.tool,
      input: action,
      tags,
    });
  }

  async handleAgentEnd(
    action: AgentFinish,
    runId: string,
    parentRunId?: string,
    tags?: string[]
  ): Promise<void> {
    logger.debug(`handleAgentEnd runId - ${runId}, parentRunId ${parentRunId}`);
    this.endTracing({
      runId,
      output: action,
      tags,
    });
  }

  async handleRetrieverStart(
    retriever: Serialized,
    query: string,
    runId: string,
    parentRunId?: string,
    tags?: string[],
    metadata?: Record<string, unknown>,
    name?: string
  ): Promise<void> {
    logger.debug(
      `handleRetrieverStart runId - ${runId}, parentRunId ${parentRunId}`
    );
    this.startTracing({
      runId,
      parentRunId,
      name: name ?? retriever.id.at(-1)?.toString() ?? "Retriever",
      type: OpikSpanType.Tool,
      input: { query },
      tags,
      metadata: {
        ...metadata,
        ...extractCallArgs(retriever, {}, metadata),
      },
    });
  }

  async handleRetrieverEnd(
    documents: unknown[],
    runId: string,
    parentRunId?: string,
    tags?: string[]
  ): Promise<void> {
    logger.debug(
      `handleRetrieverEnd runId - ${runId}, parentRunId ${parentRunId}`
    );
    this.endTracing({
      runId,
      output: { documents },
      tags,
    });
  }

  async handleRetrieverError(
    error: Error,
    runId: string,
    parentRunId?: string,
    tags?: string[]
  ): Promise<void> {
    logger.debug(
      `handleRetrieverError runId - ${runId}, parentRunId ${parentRunId}`
    );
    this.endTracing({
      runId,
      error,
      tags,
    });
  }

  async flushAsync(): Promise<void> {
    return this.client.flush();
  }
}
