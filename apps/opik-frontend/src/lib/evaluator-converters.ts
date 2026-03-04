import { BarChart3, Bot, Wrench } from "lucide-react";
import type { LucideIcon } from "lucide-react";
import {
  LLMJudgeConfig,
  MetricType,
  MetricConfig,
  EvaluatorDisplayRow,
  StringMatchConfig,
  ThresholdConfig,
  METRIC_TYPE_LABELS,
} from "@/types/evaluation-suites";
import { Evaluator } from "@/types/datasets";

interface LLMJudgeBESchemaItem {
  name: string;
  type: string;
  description: string;
}

// Keep in sync with the backend's expected config structure for the llm_judge evaluator type.
// The `schema` array is populated dynamically from FE assertions.
export const DEFAULT_LLM_JUDGE_BE_CONFIG = {
  version: "1",
  name: "llm_judge",
  model: {
    name: null,
    temperature: null,
    seed: null,
    customParameters: null,
  },
  messages: [
    {
      role: "SYSTEM",
      content:
        "You are an expert judge tasked with evaluating if an AI agent's output satisfies a set of assertions.\n\nFor each assertion, provide:\n- score: true if the assertion passes, false if it fails\n- reason: A brief explanation of your judgment\n- confidence: A float between 0.0 and 1.0 indicating how confident you are in your judgment\n",
    },
    {
      role: "USER",
      content:
        "## Input\nThe INPUT section contains all data that the agent received. This may include the actual user query, conversation history, context, metadata, or other structured information. Identify the core user request within this data.\n\n{input}\n\n## Output\nThe OUTPUT section contains all data produced by the agent. This may include the agent's response text, tool calls, intermediate results, metadata, or other structured information. Focus on the substantive response when evaluating assertions.\n\n{output}\n\n## Assertions\nEvaluate each of the following assertions against the agent's output:\n\n{assertions}\n",
    },
  ],
  variables: {
    input: "input",
    output: "output",
  },
} as const;

export function parseLLMJudgeBEConfig(
  beConfig: Record<string, unknown>,
): LLMJudgeConfig {
  const schema = (beConfig as { schema?: LLMJudgeBESchemaItem[] }).schema ?? [];
  return {
    assertions: schema.map((item) => item.name),
  };
}

export function buildLLMJudgeBEConfig(
  feConfig: LLMJudgeConfig,
  originalBEConfig?: Record<string, unknown>,
): Record<string, unknown> {
  const base = originalBEConfig ?? DEFAULT_LLM_JUDGE_BE_CONFIG;
  const schema: LLMJudgeBESchemaItem[] = (feConfig.assertions ?? []).map(
    (assertion) => ({
      name: assertion,
      type: "BOOLEAN",
      description: assertion,
    }),
  );

  return { ...base, schema };
}

export function parseBEEvaluatorConfig(
  type: string,
  beConfig: Record<string, unknown>,
): MetricConfig {
  if (type === MetricType.LLM_AS_JUDGE) {
    return parseLLMJudgeBEConfig(beConfig);
  }
  return beConfig as unknown as MetricConfig;
}

// `originalBEConfig` preserves non-schema fields (model, messages, etc.) for LLM_AS_JUDGE edits.
// For other metric types the FE config maps 1:1 to the BE config, so it is passed through as-is.
export function buildBEEvaluatorConfig(
  type: string,
  feConfig: MetricConfig,
  originalBEConfig?: Record<string, unknown>,
): Record<string, unknown> {
  if (type === MetricType.LLM_AS_JUDGE) {
    return buildLLMJudgeBEConfig(feConfig as LLMJudgeConfig, originalBEConfig);
  }
  return feConfig as unknown as Record<string, unknown>;
}

// --- Shared display utilities ---

export function formatEvaluatorConfig(
  type: MetricType,
  config: MetricConfig,
): string {
  switch (type) {
    case MetricType.CONTAINS:
    case MetricType.EQUALS: {
      const c = config as StringMatchConfig;
      const caseSuffix = c.case_sensitive
        ? "case sensitive"
        : "case insensitive";
      return `"${c.value}", ${caseSuffix}`;
    }
    case MetricType.LEVENSHTEIN_RATIO:
    case MetricType.HALLUCINATION:
    case MetricType.MODERATION:
      return `threshold: ${(config as ThresholdConfig).threshold}`;
    case MetricType.LLM_AS_JUDGE: {
      const assertions = (config as LLMJudgeConfig).assertions ?? [];
      if (assertions.length === 0) return "";
      if (assertions.length === 1) return assertions[0];
      return assertions.map((a, i) => `${i + 1}. ${a}`).join("\n");
    }
    default:
      return "";
  }
}

export function getMetricIcon(type: MetricType): LucideIcon {
  switch (type) {
    case MetricType.LLM_AS_JUDGE:
      return Bot;
    case MetricType.CONTAINS:
    case MetricType.EQUALS:
      return Wrench;
    case MetricType.LEVENSHTEIN_RATIO:
    case MetricType.HALLUCINATION:
    case MetricType.MODERATION:
      return BarChart3;
  }
}

export function getSectionLabel(type: MetricType): string {
  switch (type) {
    case MetricType.LLM_AS_JUDGE:
      return "ASSERTIONS";
    case MetricType.CONTAINS:
    case MetricType.EQUALS:
      return "PATTERN";
    case MetricType.LEVENSHTEIN_RATIO:
    case MetricType.HALLUCINATION:
    case MetricType.MODERATION:
      return "THRESHOLD";
  }
}

// --- Display layer: expand server evaluators into UI rows ---

const VALID_METRIC_TYPES = new Set<string>(Object.values(MetricType));

function isValidMetricType(type: string): type is MetricType {
  return VALID_METRIC_TYPES.has(type);
}

function getServerEvaluatorId(evaluator: Evaluator): string {
  return `server_${evaluator.name}_${evaluator.type}`;
}

export function expandEvaluatorsToRows(
  evaluators: Evaluator[],
): EvaluatorDisplayRow[] {
  return evaluators
    .filter((evaluator) => {
      if (!isValidMetricType(evaluator.type)) {
        console.warn(
          `Unknown evaluator type "${evaluator.type}" for "${evaluator.name}", skipping`,
        );
        return false;
      }
      return true;
    })
    .map((evaluator) => {
      const id = getServerEvaluatorId(evaluator);
      return {
        id,
        evaluatorId: id,
        name: evaluator.name,
        type: evaluator.type as MetricType,
        config: parseBEEvaluatorConfig(evaluator.type, evaluator.config),
      };
    });
}

export function applyEvaluatorEdits(
  originalRows: EvaluatorDisplayRow[],
  added: Map<string, EvaluatorDisplayRow>,
  edited: Map<string, Partial<EvaluatorDisplayRow>>,
  deleted: Set<string>,
): EvaluatorDisplayRow[] {
  const surviving = originalRows
    .filter((row) => !deleted.has(row.id))
    .map((row) => {
      const edits = edited.get(row.id);
      return edits ? { ...row, ...edits } : row;
    });

  return [...surviving, ...added.values()];
}

export function formatEvaluatorsForExport(
  evaluators: Evaluator[],
): Array<Record<string, unknown>> {
  return evaluators
    .filter((e) => isValidMetricType(e.type))
    .map((e) => ({
      name: e.name,
      type: METRIC_TYPE_LABELS[e.type as MetricType],
      ...parseBEEvaluatorConfig(e.type, e.config),
    }));
}

// --- Serialization boundary: reconstruct Evaluator[] from draft state ---

export function reconstructEvaluators(
  originalEvaluators: Evaluator[],
  added: Map<string, EvaluatorDisplayRow>,
  edited: Map<string, Partial<EvaluatorDisplayRow>>,
  deleted: Set<string>,
): Evaluator[] {
  const surviving: Evaluator[] = originalEvaluators
    .map((evaluator) => ({
      evaluator,
      id: getServerEvaluatorId(evaluator),
    }))
    .filter(({ id }) => !deleted.has(id))
    .map(({ evaluator, id }) => {
      const edits = edited.get(id);
      if (!edits) return evaluator;

      const mergedType = (edits.type as string) ?? evaluator.type;
      const mergedConfig = edits.config
        ? buildBEEvaluatorConfig(mergedType, edits.config, evaluator.config)
        : evaluator.config;

      return {
        name: edits.name ?? evaluator.name,
        type: mergedType,
        config: mergedConfig,
      };
    });

  const addedEvaluators: Evaluator[] = Array.from(added.values()).map(
    (row) => ({
      name: row.name,
      type: row.type,
      config: buildBEEvaluatorConfig(row.type, row.config),
    }),
  );

  return [...surviving, ...addedEvaluators];
}
