import { LLMJudgeConfig, MetricType } from "@/types/evaluation-suites";
import { Evaluator } from "@/types/datasets";

const ASSERTION_SCHEMA_TYPE = "BOOLEAN";

interface LLMJudgeBESchemaItem {
  name: string;
  type: string;
  description: string;
}

// Keep in sync with the backend's expected config structure for the llm_judge type.
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
      type: ASSERTION_SCHEMA_TYPE,
      description: assertion,
    }),
  );

  return { ...base, schema };
}

// --- Assertion-centric API ---

export function extractAssertions(evaluators: Evaluator[]): string[] {
  const first = evaluators[0];
  if (!first || first.type !== MetricType.LLM_AS_JUDGE) return [];
  return parseLLMJudgeBEConfig(first.config).assertions;
}

export function packAssertions(
  assertions: string[],
  originalEvaluator?: Evaluator,
): Evaluator {
  const originalConfig = originalEvaluator?.config;
  const config = buildLLMJudgeBEConfig({ assertions }, originalConfig);
  return {
    name: originalEvaluator?.name ?? "llm_judge",
    type: originalEvaluator?.type ?? MetricType.LLM_AS_JUDGE,
    config,
  };
}
