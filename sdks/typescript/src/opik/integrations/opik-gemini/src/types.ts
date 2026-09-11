import type { Opik, Span, Trace } from "opik";

export type OpikParent = Trace | Span;

export type TrackOpikConfig = {
  /**
   * Project name for this trace
   */
  projectName?: string;

  /**
   * Parent span or trace for this generation
   */
  parent?: OpikParent;

  /**
   * Generation name (defaults to "ClassName.methodName")
   */
  generationName?: string;

  /**
   * Trace metadata (tags, custom fields)
   */
  traceMetadata?: Record<string, unknown> & {
    tags?: string[];
  };

  /**
   * Opik client instance
   */
  client?: Opik;
};

export type TracingConfig = Required<
  Pick<TrackOpikConfig, "generationName" | "client">
> &
  Pick<TrackOpikConfig, "parent" | "traceMetadata"> & {
    provider: string;
  };

export type OpikExtension = {
  /**
   * Flush all pending traces and spans to Opik backend
   */
  flush: () => Promise<void>;
};

export type GenericMethod = (...args: unknown[]) => unknown;

export type ObservationData = {
  name: string;
  startTime: Date;
  input: Record<string, unknown>;
  model?: string;
  provider: string;
  metadata: Record<string, unknown>;
  tags: string[];
};

// Gemini SDK types
export interface GeminiGenerateContentParams {
  contents: unknown;
  config?: {
    generationConfig?: Record<string, unknown>;
    safetySettings?: Record<string, unknown>[];
    tools?: Record<string, unknown>[];
    temperature?: number;
    maxOutputTokens?: number;
    topP?: number;
    topK?: number;
    candidateCount?: number;
    stopSequences?: string[];
  };
}

export interface GeminiPart {
  text?: string;
  functionCall?: Record<string, unknown>;
  function_call?: Record<string, unknown>;
}

export interface GeminiContent {
  parts?: GeminiPart[];
  role?: string;
}

export interface GeminiCandidate {
  content?: GeminiContent;
  finishReason?: string;
  finish_reason?: string;
  safetyRatings?: Record<string, unknown>[];
  safety_ratings?: Record<string, unknown>[];
}

export interface GeminiTokenDetails {
  audioTokens?: number;
  textTokens?: number;
  imageTokens?: number;
  videoTokens?: number;
}

export interface GeminiUsageMetadata {
  promptTokenCount?: number;
  prompt_token_count?: number;
  candidatesTokenCount?: number;
  candidates_token_count?: number;
  totalTokenCount?: number;
  total_token_count?: number;
  cachedContentTokenCount?: number;
  cached_content_token_count?: number;
  promptTokensDetails?: GeminiTokenDetails;
  prompt_tokens_details?: GeminiTokenDetails;
  candidatesTokensDetails?: GeminiTokenDetails;
  candidates_tokens_details?: GeminiTokenDetails;
}

export interface GeminiFunctionCall {
  name: string;
  args: Record<string, unknown>;
}

export interface GeminiResponse {
  candidates?: GeminiCandidate[];
  usageMetadata?: GeminiUsageMetadata;
  usage_metadata?: GeminiUsageMetadata;
  usage?: GeminiUsageMetadata;
  modelVersion?: string;
  model_version?: string;
  promptFeedback?: Record<string, unknown>;
  prompt_feedback?: Record<string, unknown>;
  text?: string;
  functionCalls?: GeminiFunctionCall[];
}

export interface ChunkResult {
  isToolCall: boolean;
  data: string;
}
