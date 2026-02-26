export { OpikClient as Opik } from "@/client/Client";
export type { FeedbackScoreData } from "@/tracer/types";
export type { OpikConfig } from "@/config/Config";
export { getTrackContext, track } from "@/decorators/track";
export { generateId } from "@/utils/generateId";
export { flushAll } from "@/utils/flushAll";
export { disableLogger, logger, setLoggerLevel } from "@/utils/logger";

export type { Span } from "@/tracer/Span";
export type { Trace } from "@/tracer/Trace";
export type { ErrorInfo } from "@/rest_api/api/types/ErrorInfo";
export type { SpanType } from "@/rest_api/api/types/SpanType";
export { SpanType as OpikSpanType } from "@/rest_api/api/types/SpanType";
export type { DatasetPublic } from "@/rest_api/api/types/DatasetPublic";
export * from "./evaluation";

// Dataset exports
export { Dataset } from "@/dataset/Dataset";
export { DatasetVersion } from "@/dataset/DatasetVersion";
export { DatasetVersionNotFoundError } from "@/errors/dataset/errors";
export type { DatasetVersionPublic } from "@/rest_api/api/types/DatasetVersionPublic";

export { Prompt, PromptType } from "@/prompt";
export { OpikQueryLanguage } from "@/query";
export type { FilterExpression } from "@/query";

export { TracesAnnotationQueue, ThreadsAnnotationQueue } from "@/annotation-queue";
export type { AnnotationQueuePublicScope as AnnotationQueueScope } from "@/rest_api/api/types/AnnotationQueuePublicScope";

// Re-export Zod to ensure consumers use the same version as the SDK
export { z } from "zod";
