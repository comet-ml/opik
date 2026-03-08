import { Opik, OpikConfig, Span, Trace } from "opik";

export type OpikParent = Trace | Span;

export interface TrackExaConfig {
  client?: Opik;
  clientConfig?: Partial<OpikConfig>;
  parent?: OpikParent;
  projectName?: string;
  generationName?: string;
  traceMetadata?: Record<string, unknown> & {
    tags?: string[];
  };
}

export interface OpikExtension {
  flush: () => Promise<void>;
}

export type GenericMethod = (...args: unknown[]) => unknown;
