import { LLM_MESSAGE_ROLE } from "@/types/llm";

export type TraceAnalyzerLLMMessage = {
  id: string;
  role: LLM_MESSAGE_ROLE.user | LLM_MESSAGE_ROLE.assistant;
  content: string;
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
  onAddChunk: (accumulatedValue: string) => void;
  signal: AbortSignal;
};

export type TraceAnalyzerRunStreamingReturn = {
  error: null | string;
};
