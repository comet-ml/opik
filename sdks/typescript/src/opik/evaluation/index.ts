/**
 * Evaluation module for Opik TypeScript SDK
 * Provides tools for evaluating LLM tasks against datasets
 */

export * from "./evaluate";
export * from "./metrics/BaseMetric";
export * from "./metrics/ExactMatch";
export * from "./metrics/Contains";
export * from "./metrics/RegexMatch";
export * from "./types";
export * from "./engine/EvaluationEngine";
export * from "./results/EvaluationResultProcessor";
