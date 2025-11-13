import {
  OPTIMIZER_TYPE,
  METRIC_TYPE,
  Optimization,
} from "@/types/optimizations";
import { LLM_MESSAGE_ROLE } from "@/types/llm";

export const DEFAULT_GEPA_OPTIMIZER_CONFIGS = {
  VERBOSE: false,
  SEED: 42,
};

export const DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS = {
  POPULATION_SIZE: 10,
  NUM_GENERATIONS: 5,
  MUTATION_RATE: 0.1,
  CROSSOVER_RATE: 0.7,
  TOURNAMENT_SIZE: 3,
  ELITISM_SIZE: 1,
  ADAPTIVE_MUTATION: false,
  ENABLE_MOO: false,
  ENABLE_LLM_CROSSOVER: false,
  OUTPUT_STYLE_GUIDANCE: "",
  INFER_OUTPUT_STYLE: false,
  N_THREADS: 1,
  VERBOSE: false,
  SEED: 42,
};

export const DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS = {
  CONVERGENCE_THRESHOLD: 0.01,
  VERBOSE: false,
  SEED: 42,
};

export const DEFAULT_EQUALS_METRIC_CONFIGS = {
  CASE_SENSITIVE: false,
};

export const DEFAULT_JSON_SCHEMA_VALIDATOR_METRIC_CONFIGS = {
  SCHEMA: {},
};

export const DEFAULT_G_EVAL_METRIC_CONFIGS = {
  TASK_INTRODUCTION: "",
  EVALUATION_CRITERIA: "",
};

export type OptimizationTemplate = Partial<Optimization> & {
  id: string;
  title: string;
  description: string;
};

export const DEMO_TEMPLATES: OptimizationTemplate[] = [
  {
    id: "hierarchical-geval",
    title: "Hierarchical Reflective + G-Eval",
    description: "Advanced optimization with custom evaluation criteria",
    dataset_id: "",
    studio_config: {
      dataset_name: "",
      prompt: {
        messages: [
          { role: LLM_MESSAGE_ROLE.system, content: "You are helpful." },
          { role: LLM_MESSAGE_ROLE.user, content: "Q: {question}" },
        ],
      },
      llm_model: {
        provider: "openai",
        name: "openai/gpt-4o-mini",
        parameters: { temperature: 0.7, max_tokens: 800 },
      },
      evaluation: {
        metrics: [
          {
            type: METRIC_TYPE.G_EVAL,
            parameters: {
              task_introduction:
                "You are evaluating how well an AI assistant answers questions based on context.",
              evaluation_criteria:
                "The answer should be accurate, relevant, and directly address the question. Consider: 1) Factual correctness, 2) Completeness of the answer, 3) Clarity and coherence",
            },
          },
        ],
      },
      optimizer: {
        type: OPTIMIZER_TYPE.HIERARCHICAL_REFLECTIVE,
        parameters: {
          convergence_threshold:
            DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS.CONVERGENCE_THRESHOLD,
          verbose: DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS.VERBOSE,
          seed: DEFAULT_HIERARCHICAL_REFLECTIVE_OPTIMIZER_CONFIGS.SEED,
        },
      },
    },
  },
  {
    id: "gepa-equals",
    title: "GEPA + Equals",
    description: "Fast optimization with exact match evaluation",
    dataset_id: "",
    studio_config: {
      dataset_name: "",
      prompt: {
        messages: [
          { role: LLM_MESSAGE_ROLE.system, content: "You are helpful." },
          { role: LLM_MESSAGE_ROLE.user, content: "Q: {question}" },
        ],
      },
      llm_model: {
        provider: "openai",
        name: "openai/gpt-4o-mini",
        parameters: { temperature: 0.7 },
      },
      evaluation: {
        metrics: [
          {
            type: METRIC_TYPE.EQUALS,
            parameters: {
              reference_key: "answer",
              case_sensitive: DEFAULT_EQUALS_METRIC_CONFIGS.CASE_SENSITIVE,
            },
          },
        ],
      },
      optimizer: {
        type: OPTIMIZER_TYPE.GEPA,
        parameters: {
          verbose: DEFAULT_GEPA_OPTIMIZER_CONFIGS.VERBOSE,
          seed: DEFAULT_GEPA_OPTIMIZER_CONFIGS.SEED,
        },
      },
    },
  },
];
