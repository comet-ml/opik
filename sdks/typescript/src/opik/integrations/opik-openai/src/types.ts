import { OpikConfig, Trace, Span } from "opik";

export type OpikParent = Trace | Span;

export interface TrackOpikConfig extends Partial<OpikConfig> {
  traceMetadata?: Record<string, unknown> & {
    tags?: string[];
  };
  generationName?: string;
  parent?: OpikParent;
}

export interface OpikExtension {
  flush: () => Promise<void>;
}

export type ObservationData = {
  name: string;
  startTime: Date;
  metadata?: Record<string, unknown>;
  input?: Record<string, unknown>;
  model?: string;
  provider?: string;
};

export const isAsyncIterable = (x: unknown): x is AsyncIterable<unknown> =>
  x != null &&
  typeof x === "object" &&
  typeof (x as AsyncIterable<unknown>)[Symbol.asyncIterator] === "function";

export type GenericMethod = (...args: unknown[]) => unknown;
