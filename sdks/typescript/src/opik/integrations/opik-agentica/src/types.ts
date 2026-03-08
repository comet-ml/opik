import { Opik, Span, Trace } from "opik";

export type OpikParent = Trace | Span;

export interface TrackOpikConfig {
  traceMetadata?: Record<string, unknown> & {
    tags?: string[];
  };
  client?: Opik;
  generationName?: string;
  parent?: OpikParent;
}

export interface TracingConfig extends TrackOpikConfig {
  generationName: string;
  client: Opik;
  methodName: string;
}

export interface OpikExtension {
  flush: () => Promise<void>;
}

export type GenericMethod = (...args: unknown[]) => unknown;

export type ObservationData = {
  name: string;
  startTime: Date;
  metadata?: Record<string, unknown>;
  input?: Record<string, unknown>;
  provider?: string;
  tags?: string[];
};

export const TRACKED_METHOD_NAMES = new Set([
  "agentic",
  "agenticTransformation",
  "call",
  "callTransformation",
  "spawn",
  "spawnTransformation",
]);

export const LLM_METHOD_NAMES = new Set([
  "agentic",
  "agenticTransformation",
  "call",
  "callTransformation",
]);

