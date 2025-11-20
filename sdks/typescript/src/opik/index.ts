export { OpikClient as Opik } from "@/client/Client";
export type { OpikConfig } from "@/config/Config";
export { getTrackContext, track } from "@/decorators/track";
export { generateId } from "@/utils/generateId";
export { flushAll } from "@/utils/flushAll";
export { disableLogger, logger, setLoggerLevel } from "@/utils/logger";

export type { Span } from "@/tracer/Span";
export type { Trace } from "@/tracer/Trace";
export type { SpanType } from "@/rest_api/api/types/SpanType";
export { SpanType as OpikSpanType } from "@/rest_api/api/types/SpanType";
export type { DatasetPublic } from "@/rest_api/api/types/DatasetPublic";
export * from "./evaluation";

export { Prompt, PromptType } from "@/prompt";
export { OpikQueryLanguage } from "@/query";
export type { FilterExpression } from "@/query";

// Re-export Zod to ensure consumers use the same version as the SDK
export { z } from "zod";
