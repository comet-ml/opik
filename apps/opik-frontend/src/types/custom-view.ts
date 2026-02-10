import { Trace, Thread } from "./traces";

/**
 * Context type for custom view - determines what data is being visualized
 */
export type ContextType = "trace" | "thread";

/**
 * Enriched thread with all traces (messages) included
 */
export interface EnrichedThread extends Thread {
  traces: Trace[];
}

/**
 * Union type for context data - can be either Trace or EnrichedThread
 */
export type ContextData = Trace | EnrichedThread;
