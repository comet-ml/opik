import isArray from "lodash/isArray";
import get from "lodash/get";

import { OptimizationStudioConfig } from "@/types/optimizations";
import { ConfigurationType } from "@/types/shared";
import { getOptimizerLabel } from "@/lib/optimizations";
import { OPTIMIZATION_METRIC_OPTIONS } from "@/constants/optimizations";

export const isRecord = (value: unknown): value is ConfigurationType =>
  typeof value === "object" && value !== null && !Array.isArray(value);

export const formatPrimitive = (value: unknown): string => {
  if (value === null || value === undefined) return "-";
  if (typeof value === "boolean") return value ? "true" : "false";
  return String(value);
};

export type MessageEntry = { role: string; content: string };

export const extractMessages = (value: unknown): MessageEntry[] | null => {
  const toEntries = (arr: unknown[]): MessageEntry[] =>
    arr.map((m) => {
      if (isRecord(m) && "content" in m) {
        return {
          role: String(get(m, "role", "")),
          content: String(get(m, "content", "")),
        };
      }
      return { role: "", content: JSON.stringify(m) };
    });

  if (isArray(value)) return toEntries(value);
  if (isRecord(value) && "messages" in value) {
    const messages = get(value, "messages", []);
    if (isArray(messages)) return toEntries(messages);
  }
  return null;
};

export const getMetricLabel = (type: string): string =>
  OPTIMIZATION_METRIC_OPTIONS.find((opt) => opt.value === type)?.label || type;

export const buildConfigFromStudioConfig = (
  studioConfig: OptimizationStudioConfig,
): ConfigurationType => {
  const config: ConfigurationType = {};

  if (studioConfig.dataset_name) {
    config.Dataset = studioConfig.dataset_name;
  }

  if (studioConfig.llm_model?.model) {
    config.Model = String(studioConfig.llm_model.model);
  }

  if (studioConfig.optimizer?.type) {
    config.Algorithm = getOptimizerLabel(studioConfig.optimizer.type);
  }

  if (studioConfig.evaluation?.metrics?.[0]?.type) {
    config.Metric = getMetricLabel(studioConfig.evaluation.metrics[0].type);
  }

  if (studioConfig.prompt?.messages) {
    config["Initial prompt"] = studioConfig.prompt.messages;
  }

  return config;
};
