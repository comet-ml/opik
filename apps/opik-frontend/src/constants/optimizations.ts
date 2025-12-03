import {
  OPTIMIZER_TYPE,
  METRIC_TYPE,
  Optimization,
} from "@/types/optimizations";
import { LLM_MESSAGE_ROLE } from "@/types/llm";
import { PROVIDER_MODEL_TYPE } from "@/types/providers";

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
  REFERENCE_KEY: "",
};

export const DEFAULT_JSON_SCHEMA_VALIDATOR_METRIC_CONFIGS = {
  SCHEMA: {
    key: "value",
  },
};

export const DEFAULT_G_EVAL_METRIC_CONFIGS = {
  TASK_INTRODUCTION:
    "You are evaluating how well an AI assistant answers questions based on context.",
  EVALUATION_CRITERIA:
    "The answer should be accurate, relevant, and directly address the question. Consider: 1) Factual correctness, 2) Completeness of the answer, 3) Clarity and coherence",
};

export const DEFAULT_LEVENSHTEIN_METRIC_CONFIGS = {
  CASE_SENSITIVE: false,
  REFERENCE_KEY: "",
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
          { role: LLM_MESSAGE_ROLE.user, content: "Q: {{question}}" },
        ],
      },
      llm_model: {
        model: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
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
          { role: LLM_MESSAGE_ROLE.user, content: "Q: {{question}}" },
        ],
      },
      llm_model: {
        model: PROVIDER_MODEL_TYPE.GPT_4O_MINI,
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
  // {
  //   id: "evolutionary-levenshtein",
  //   title: "Evolutionary + Levenshtein",
  //   description: "Genetic algorithm optimization with fuzzy text matching",
  //   dataset_id: "",
  //   studio_config: {
  //     dataset_name: "",
  //     prompt: {
  //       messages: [
  //         { role: LLM_MESSAGE_ROLE.system, content: "You are helpful." },
  //         { role: LLM_MESSAGE_ROLE.user, content: "Q: {{question}}" },
  //       ],
  //     },
  //     llm_model: {
  //       provider: "openai",
  //       name: "openai/gpt-4o-mini",
  //       parameters: { temperature: 0.7 },
  //     },
  //     evaluation: {
  //       metrics: [
  //         {
  //           type: METRIC_TYPE.LEVENSHTEIN,
  //           parameters: {
  //             case_sensitive: DEFAULT_LEVENSHTEIN_METRIC_CONFIGS.CASE_SENSITIVE,
  //             reference_key: DEFAULT_LEVENSHTEIN_METRIC_CONFIGS.REFERENCE_KEY,
  //           },
  //         },
  //       ],
  //     },
  //     optimizer: {
  //       type: OPTIMIZER_TYPE.EVOLUTIONARY,
  //       parameters: {
  //         population_size:
  //           DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.POPULATION_SIZE,
  //         num_generations:
  //           DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.NUM_GENERATIONS,
  //         mutation_rate: DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.MUTATION_RATE,
  //         crossover_rate: DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.CROSSOVER_RATE,
  //         tournament_size:
  //           DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.TOURNAMENT_SIZE,
  //         elitism_size: DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.ELITISM_SIZE,
  //         adaptive_mutation:
  //           DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.ADAPTIVE_MUTATION,
  //         enable_moo: DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.ENABLE_MOO,
  //         enable_llm_crossover:
  //           DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.ENABLE_LLM_CROSSOVER,
  //         output_style_guidance:
  //           DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.OUTPUT_STYLE_GUIDANCE,
  //         infer_output_style:
  //           DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.INFER_OUTPUT_STYLE,
  //         n_threads: DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.N_THREADS,
  //         verbose: DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.VERBOSE,
  //         seed: DEFAULT_EVOLUTIONARY_OPTIMIZER_CONFIGS.SEED,
  //       },
  //     },
  //   },
  // },
];
