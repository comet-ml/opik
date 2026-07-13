import {
  OPTIMIZER_TYPE,
  OPTIMIZATION_STATUS,
  METRIC_TYPE,
  Optimization,
  OptimizerParameters,
  MetricParameters,
  AggregatedCandidate,
  ExperimentOptimizationMetadata,
} from "@/types/optimizations";
import { AggregatedFeedbackScore } from "@/types/shared";
import { aggregateExperimentMetrics } from "@/lib/experiment-metrics";
import { getFeedbackScore } from "@/lib/feedback-scores";
import { Experiment, EVALUATION_METHOD } from "@/types/datasets";
import { extractMetricNameFromPythonCode } from "@/lib/rules";
import {
  DEFAULT_GEPA_OPTIMIZER_CONFIGS,
  DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS,
  DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS,
  DEFAULT_EQUALS_METRIC_CONFIGS,
  DEFAULT_JSON_SCHEMA_VALIDATOR_METRIC_CONFIGS,
  DEFAULT_G_EVAL_METRIC_CONFIGS,
  DEFAULT_LEVENSHTEIN_METRIC_CONFIGS,
  DEFAULT_NUMERICAL_SIMILARITY_METRIC_CONFIGS,
  DEFAULT_CODE_METRIC_CONFIGS,
  OPTIMIZER_OPTIONS,
} from "@/constants/optimizations";
import { DEFAULT_ANTHROPIC_CONFIGS } from "@/constants/llm";
import {
  getDefaultTemperatureForModel,
  supportsSamplingParams,
} from "@/lib/modelUtils";
import {
  LLMAnthropicConfigsType,
  LLMOpenAIConfigsType,
  LLMPromptConfigsType,
  PROVIDER_MODEL_TYPE,
  PROVIDER_TYPE,
  COMPOSED_PROVIDER_TYPE,
} from "@/types/providers";
import { parseComposedProviderType } from "@/lib/provider";
import { COLUMN_TYPE } from "@/types/shared";
import { Filters } from "@/types/filters";

export const getBaselineCandidate = (
  candidates?: AggregatedCandidate[],
): AggregatedCandidate | undefined =>
  candidates?.find((c) => c.stepIndex === 0);

export const getOptimizerLabel = (type: string): string => {
  return OPTIMIZER_OPTIONS.find((opt) => opt.value === type)?.label || type;
};

// Optimizer type from a run: studio_config, falling back to metadata.optimizer
// (untyped) for older/non-Studio runs. Undefined when neither is present.
export const getOptimizationOptimizerType = (
  row: Pick<Optimization, "studio_config" | "metadata">,
): string | undefined =>
  row.studio_config?.optimizer?.type ??
  (row.metadata as { optimizer?: string } | undefined)?.optimizer;

export const getBestOptimizationScore = (
  row: Pick<
    Optimization,
    "feedback_scores" | "experiment_scores" | "objective_name"
  >,
): AggregatedFeedbackScore | undefined =>
  getFeedbackScore(row.feedback_scores ?? [], row.objective_name) ??
  getFeedbackScore(row.experiment_scores ?? [], row.objective_name);

export const extractMetricNameFromCode = (code: string): string => {
  return extractMetricNameFromPythonCode(code) || "code";
};

// Dataset columns a code metric REQUIRES via `kwargs`. The backend splats the
// dataset item into `score(**kwargs)`, so a required `kwargs["x"]` /
// default-less `kwargs.get("x")` access references a dataset column that must
// exist in the item source (mirrors extractMetricNameFromPythonCode's
// static-scan approach).
//
// Only *required* accesses are returned (they gate submit):
//   - `kwargs["x"]`               -> required (KeyError if absent)
//   - `kwargs.get("x")`           -> required (no fallback -> None, likely a bug)
//   - `kwargs.get("x", default)`  -> NOT required (a default is supplied), so it
//                                    must never hard-block submit.
//
// Comments and string/docstring literals are stripped (best-effort, via a small
// state machine) before the scan, so a `kwargs.get("x")` mention buried inside a
// docstring or `# comment` is not mistaken for a real column access. Dynamic
// access (e.g. `kwargs.get(var)`) can't be resolved statically and stays a
// runtime concern. `output` is always injected by the backend, so it is never
// treated as a required column.
export const extractKwargsKeysFromPython = (code: string): string[] => {
  if (!code) return [];

  const keys = new Set<string>();
  const n = code.length;
  const isIdentChar = (c: string | undefined) =>
    c !== undefined && /[A-Za-z0-9_]/.test(c);

  // Read a quoted string starting at `code[start]` (a quote char). Returns the
  // literal contents and the index just past the closing quote.
  const readQuoted = (
    start: number,
    quote: string,
  ): { value: string; end: number } => {
    let k = start + 1;
    let value = "";
    while (k < n) {
      if (code[k] === "\\") {
        value += code[k + 1] ?? "";
        k += 2;
        continue;
      }
      if (code[k] === quote) {
        k += 1;
        break;
      }
      value += code[k];
      k += 1;
    }
    return { value, end: k };
  };

  let i = 0;
  while (i < n) {
    const ch = code[i];

    // Skip `#` comments to end of line.
    if (ch === "#") {
      while (i < n && code[i] !== "\n") i += 1;
      continue;
    }

    // Skip string / docstring literals (single, double, and triple-quoted).
    if (ch === '"' || ch === "'") {
      const triple = code.slice(i, i + 3) === ch.repeat(3);
      const closing = triple ? ch.repeat(3) : ch;
      i += closing.length;
      while (i < n) {
        if (code[i] === "\\") {
          i += 2;
          continue;
        }
        if (code.slice(i, i + closing.length) === closing) {
          i += closing.length;
          break;
        }
        i += 1;
      }
      continue;
    }

    // A `kwargs` identifier access in code (not part of a longer identifier).
    if (
      code.startsWith("kwargs", i) &&
      !isIdentChar(code[i - 1]) &&
      !isIdentChar(code[i + 6])
    ) {
      let j = i + 6;
      while (j < n && /\s/.test(code[j])) j += 1;

      // kwargs["x"] / kwargs['x'] -> required.
      if (code[j] === "[") {
        j += 1;
        while (j < n && /\s/.test(code[j])) j += 1;
        if (code[j] === '"' || code[j] === "'") {
          const { value, end } = readQuoted(j, code[j]);
          if (value && value !== "output") keys.add(value);
          i = end;
          continue;
        }
      }

      // kwargs.get("x") / kwargs.get("x", default)
      if (code.startsWith(".get", j)) {
        j += 4;
        while (j < n && /\s/.test(code[j])) j += 1;
        if (code[j] === "(") {
          j += 1;
          while (j < n && /\s/.test(code[j])) j += 1;
          if (code[j] === '"' || code[j] === "'") {
            const { value, end } = readQuoted(j, code[j]);
            // A default arg (a `,` after the key) makes the column optional.
            let k = end;
            while (k < n && /\s/.test(code[k])) k += 1;
            const hasDefault = code[k] === ",";
            if (value && value !== "output" && !hasDefault) keys.add(value);
            i = end;
            continue;
          }
        }
      }
    }

    i += 1;
  }

  return [...keys];
};

export const getObjectiveLabel = (
  isTestSuite?: boolean,
  objectiveName?: string,
): string => (isTestSuite ? "Pass rate" : objectiveName ?? "Accuracy");

export const MAX_EXPERIMENTS_LOADED = 1000;

export const IN_PROGRESS_OPTIMIZATION_STATUSES: OPTIMIZATION_STATUS[] = [
  OPTIMIZATION_STATUS.RUNNING,
  OPTIMIZATION_STATUS.INITIALIZED,
];

export const OPTIMIZATION_ACTIVE_REFETCH_INTERVAL = 5000;

export const ACTIVE_OPTIMIZATION_FILTER: Filters = [
  {
    id: "status-running",
    field: "status",
    type: COLUMN_TYPE.string,
    operator: "=",
    value: OPTIMIZATION_STATUS.RUNNING,
  },
];

export const getDefaultOptimizerConfig = (
  optimizerType: OPTIMIZER_TYPE,
): Partial<OptimizerParameters> => {
  switch (optimizerType) {
    case OPTIMIZER_TYPE.GEPA:
      return {
        verbose: DEFAULT_GEPA_OPTIMIZER_CONFIGS.VERBOSE,
        seed: DEFAULT_GEPA_OPTIMIZER_CONFIGS.SEED,
      };
    case OPTIMIZER_TYPE.EVOLUTIONARY:
      return {
        population_size: DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.POPULATION_SIZE,
        num_generations: DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.NUM_GENERATIONS,
        mutation_rate: DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.MUTATION_RATE,
        crossover_rate: DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.CROSSOVER_RATE,
        tournament_size: DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.TOURNAMENT_SIZE,
        elitism_size: DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.ELITISM_SIZE,
        adaptive_mutation:
          DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.ADAPTIVE_MUTATION,
        enable_moo: DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.ENABLE_MOO,
        enable_llm_crossover:
          DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.ENABLE_LLM_CROSSOVER,
        output_style_guidance:
          DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.OUTPUT_STYLE_GUIDANCE,
        infer_output_style:
          DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.INFER_OUTPUT_STYLE,
        n_threads: DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.N_THREADS,
        verbose: DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.VERBOSE,
        seed: DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.SEED,
      };
    case OPTIMIZER_TYPE.HIERARCHICAL_REFLECTIVE:
      return {
        convergence_threshold:
          DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS.CONVERGENCE_THRESHOLD,
        verbose: DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS.VERBOSE,
        seed: DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS.SEED,
      };
    default:
      return {};
  }
};

export const getDefaultMetricConfig = (
  metricType: METRIC_TYPE,
): Partial<MetricParameters> => {
  switch (metricType) {
    case METRIC_TYPE.EQUALS:
      return {
        reference_key: DEFAULT_EQUALS_METRIC_CONFIGS.REFERENCE_KEY,
        case_sensitive: DEFAULT_EQUALS_METRIC_CONFIGS.CASE_SENSITIVE,
      };
    case METRIC_TYPE.JSON_SCHEMA_VALIDATOR:
      return {
        reference_key:
          DEFAULT_JSON_SCHEMA_VALIDATOR_METRIC_CONFIGS.REFERENCE_KEY,
      };
    case METRIC_TYPE.G_EVAL:
      return {
        task_introduction: DEFAULT_G_EVAL_METRIC_CONFIGS.TASK_INTRODUCTION,
        evaluation_criteria: DEFAULT_G_EVAL_METRIC_CONFIGS.EVALUATION_CRITERIA,
      };
    case METRIC_TYPE.LEVENSHTEIN:
      return {
        case_sensitive: DEFAULT_LEVENSHTEIN_METRIC_CONFIGS.CASE_SENSITIVE,
        reference_key: DEFAULT_LEVENSHTEIN_METRIC_CONFIGS.REFERENCE_KEY,
      };
    case METRIC_TYPE.NUMERICAL_SIMILARITY:
      return {
        reference_key:
          DEFAULT_NUMERICAL_SIMILARITY_METRIC_CONFIGS.REFERENCE_KEY,
      };
    case METRIC_TYPE.CODE:
      return {
        code: DEFAULT_CODE_METRIC_CONFIGS.CODE,
      };
    default:
      return {};
  }
};

// @ToDo: remove when we support all params
export const getOptimizationDefaultConfigByProvider = (
  provider: COMPOSED_PROVIDER_TYPE,
  model?: PROVIDER_MODEL_TYPE | "",
): LLMPromptConfigsType => {
  const providerType = parseComposedProviderType(provider);

  if (providerType === PROVIDER_TYPE.OPEN_AI) {
    return {
      temperature: getDefaultTemperatureForModel(model),
    } as LLMOpenAIConfigsType;
  }

  if (providerType === PROVIDER_TYPE.ANTHROPIC) {
    return {
      temperature: supportsSamplingParams(model)
        ? DEFAULT_ANTHROPIC_CONFIGS.TEMPERATURE
        : undefined,
    } as LLMAnthropicConfigsType;
  }

  return {};
};

export const checkIsTestSuite = (experiments: Experiment[]): boolean => {
  return experiments.some(
    (e) => e.evaluation_method === EVALUATION_METHOD.TEST_SUITE,
  );
};

export const getOptimizationMetadata = (
  metadata: object | undefined,
  experimentId: string,
): ExperimentOptimizationMetadata => {
  if (metadata) {
    const m = metadata as Record<string, unknown>;
    if (typeof m.step_index === "number") {
      return {
        step_index: m.step_index,
        candidate_id: (m.candidate_id as string) ?? "",
        parent_candidate_ids: (m.parent_candidate_ids as string[]) ?? [],
        configuration: m.configuration as
          | ExperimentOptimizationMetadata["configuration"]
          | undefined,
      };
    }
    return {
      step_index: -1,
      candidate_id: experimentId,
      parent_candidate_ids: [],
      configuration: m.configuration as
        | ExperimentOptimizationMetadata["configuration"]
        | undefined,
    };
  }
  return {
    step_index: -1,
    candidate_id: experimentId,
    parent_candidate_ids: [],
  };
};

export const aggregateCandidates = (
  experiments: Experiment[],
  objectiveName: string | undefined,
): AggregatedCandidate[] => {
  const groups = new Map<
    string,
    {
      experiments: Experiment[];
      meta: ExperimentOptimizationMetadata;
    }
  >();

  for (const exp of experiments) {
    const meta = getOptimizationMetadata(exp.metadata, exp.id);
    const key = meta.candidate_id;
    const existing = groups.get(key);
    if (existing) {
      existing.experiments.push(exp);
      if (
        meta.step_index >= 0 &&
        (existing.meta.step_index < 0 ||
          meta.step_index < existing.meta.step_index)
      ) {
        existing.meta = meta;
      }
    } else {
      groups.set(key, { experiments: [exp], meta });
    }
  }

  const candidates: AggregatedCandidate[] = [];

  for (const [candidateId, group] of groups) {
    const exps = group.experiments.sort((a, b) =>
      a.created_at.localeCompare(b.created_at),
    );
    const meta = group.meta;

    const metrics = aggregateExperimentMetrics(exps, objectiveName);

    candidates.push({
      id: candidateId,
      candidateId,
      stepIndex: meta.step_index,
      parentCandidateIds: meta.parent_candidate_ids,
      trialNumber: 0,
      score: metrics.score,
      runtimeCost: metrics.cost,
      latencyP50: metrics.latency,
      totalTraceCount: metrics.totalTraceCount,
      totalDatasetItemCount: metrics.totalDatasetItemCount,
      passedCount: metrics.passedCount,
      totalCount: metrics.totalCount,
      experimentIds: exps.map((e) => e.id),
      name: exps[0].name,
      created_at: exps[0].created_at,
    });
  }

  candidates.sort((a, b) => a.created_at.localeCompare(b.created_at));

  return candidates.map((c, i) => {
    const isOldStyle = c.stepIndex === -1;
    return {
      ...c,
      stepIndex: isOldStyle ? i : c.stepIndex,
      parentCandidateIds:
        isOldStyle && i > 0
          ? [candidates[i - 1].candidateId]
          : c.parentCandidateIds,
      trialNumber: i + 1,
    };
  });
};

export const mergeExperimentScores = (
  feedbackScores: AggregatedFeedbackScore[] | undefined,
  experimentScores: AggregatedFeedbackScore[] | undefined,
): AggregatedFeedbackScore[] => {
  if (!experimentScores?.length) return [];
  const existingNames = new Set(feedbackScores?.map((s) => s.name) ?? []);
  return experimentScores.filter((s) => !existingNames.has(s.name));
};

export const CANDIDATE_SORT_FIELD_MAP: Record<
  string,
  keyof AggregatedCandidate | undefined
> = {
  name: "trialNumber",
  step: "stepIndex",
  id: "id",
  objective_name: "score",
  runtime_cost: "runtimeCost",
  latency: "latencyP50",
  trace_count: "totalDatasetItemCount",
  created_at: "created_at",
};

export const sortCandidates = (
  candidates: AggregatedCandidate[],
  sortedColumns: { id: string; desc: boolean }[],
): AggregatedCandidate[] => {
  if (!sortedColumns.length) return candidates;

  const { id: columnId, desc } = sortedColumns[0];
  const field = CANDIDATE_SORT_FIELD_MAP[columnId];
  if (!field) return candidates;

  return [...candidates].sort((a, b) => {
    const aVal = a[field];
    const bVal = b[field];

    if (aVal == null && bVal == null) return 0;
    if (aVal == null) return 1;
    if (bVal == null) return -1;

    let cmp: number;
    if (typeof aVal === "number" && typeof bVal === "number") {
      cmp = aVal - bVal;
    } else {
      cmp = String(aVal).localeCompare(String(bVal));
    }

    return desc ? -cmp : cmp;
  });
};

export const convertOptimizationVariableFormat = (
  content: string | unknown,
): string | unknown => {
  if (typeof content === "string") {
    // Replace {var} with {{var}} for playground compatibility
    // Use negative lookbehind and lookahead to avoid matching already-converted {{var}}
    // This regex matches { that is not preceded by { and followed by content and } not followed by }
    return content.replace(/(?<!\{)\{([^{}]+)\}(?!\})/g, "{{$1}}");
  }

  // If content is an array (multimodal content), process each text part
  if (Array.isArray(content)) {
    return content.map((part) => {
      if (
        part &&
        typeof part === "object" &&
        part.type === "text" &&
        typeof part.text === "string"
      ) {
        return {
          ...part,
          text: part.text.replace(/(?<!\{)\{([^{}]+)\}(?!\})/g, "{{$1}}"),
        };
      }
      return part;
    });
  }

  return content;
};
