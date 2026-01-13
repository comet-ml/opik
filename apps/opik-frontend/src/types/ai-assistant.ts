import { LLM_MESSAGE_ROLE } from "@/types/llm";

export enum MESSAGE_TYPE {
  tool_call = "tool_call",
  tool_complete = "tool_complete",
  response = "response",
}

export type ToolCall = {
  id: string;
  name: string;
  display_name: string;
  completed?: boolean;
};

export type ToolResponse = {
  id: string;
  name: string;
};

export type TraceAnalyzerLLMMessage = {
  id: string;
  role: LLM_MESSAGE_ROLE.user | LLM_MESSAGE_ROLE.assistant;
  content: string;
  messageType?: MESSAGE_TYPE;
  toolCalls?: ToolCall[];
  isLoading?: boolean;
  isError?: boolean;
};

export type TraceAnalyzerHistoryResponse = {
  content: TraceAnalyzerLLMMessage[];
};

export type TraceLLMChatType = {
  value: string;
  messages: TraceAnalyzerLLMMessage[];
};

export type TraceAnalyzerRunStreamingArgs = {
  message: string;
  onAddChunk: (data: {
    messageType: MESSAGE_TYPE;
    eventId?: string;
    content?: string;
    partial?: boolean;
    toolCall?: ToolCall;
    toolResponse?: ToolResponse;
  }) => void;
  signal: AbortSignal;
};

export type TraceAnalyzerRunStreamingReturn = {
  error: null | string;
};
