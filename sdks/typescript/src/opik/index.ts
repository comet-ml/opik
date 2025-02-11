export { OpikClient as Opik } from "@/client/Client";
export { OpikConfig } from "@/config/Config";
export { track, trackOpikClient } from "@/decorators/track";
export { flushAll } from "@/flushAll";

export type { Span } from "@/tracer/Span";
export type { Trace } from "@/tracer/Trace";
