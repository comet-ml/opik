import { Experiment, EXPERIMENT_TYPE } from "@/types/datasets";

/**
 * Mock data for Experiment Leaderboard Widget prototype
 * This file contains realistic experiment data with feedback scores for testing
 */

export const mockLeaderboardExperiments: Experiment[] = [
  {
    id: "exp-1",
    name: "GPT-4 Temperature 0.7",
    dataset_id: "dataset-1",
    dataset_name: "Customer Support Dataset",
    type: EXPERIMENT_TYPE.REGULAR,
    status: "completed",
    feedback_scores: [
      { name: "accuracy", value: 0.92 },
      { name: "hallucination_rate", value: 0.08 },
      { name: "relevance", value: 0.89 },
      { name: "helpfulness", value: 0.91 },
    ],
    experiment_scores: [
      { name: "mean_score", value: 0.90 },
    ],
    duration: {
      p50: 125000, // 125 seconds
      p90: 150000,
      p99: 200000,
    },
    total_estimated_cost: 2.45,
    trace_count: 150,
    created_at: "2026-01-05T10:30:00Z",
    last_updated_at: "2026-01-05T12:45:00Z",
    metadata: {
      provider: "OpenAI",
      model_name: "gpt-4",
      temperature: 0.7,
      max_tokens: 1000,
    },
    comments: [],
    prompt_versions: [],
  },
  {
    id: "exp-2",
    name: "GPT-4 Temperature 0.3",
    dataset_id: "dataset-1",
    dataset_name: "Customer Support Dataset",
    type: EXPERIMENT_TYPE.REGULAR,
    status: "completed",
    feedback_scores: [
      { name: "accuracy", value: 0.95 },
      { name: "hallucination_rate", value: 0.05 },
      { name: "relevance", value: 0.93 },
      { name: "helpfulness", value: 0.89 },
    ],
    experiment_scores: [
      { name: "mean_score", value: 0.93 },
    ],
    duration: {
      p50: 118000,
      p90: 140000,
      p99: 180000,
    },
    total_estimated_cost: 2.38,
    trace_count: 150,
    created_at: "2026-01-05T08:15:00Z",
    last_updated_at: "2026-01-05T10:20:00Z",
    metadata: {
      provider: "OpenAI",
      model_name: "gpt-4",
      temperature: 0.3,
      max_tokens: 1000,
    },
    comments: [],
    prompt_versions: [],
  },
  {
    id: "exp-3",
    name: "Claude 3.5 Sonnet Default",
    dataset_id: "dataset-1",
    dataset_name: "Customer Support Dataset",
    type: EXPERIMENT_TYPE.REGULAR,
    status: "completed",
    feedback_scores: [
      { name: "accuracy", value: 0.88 },
      { name: "hallucination_rate", value: 0.12 },
      { name: "relevance", value: 0.91 },
      { name: "helpfulness", value: 0.93 },
    ],
    experiment_scores: [
      { name: "mean_score", value: 0.91 },
    ],
    duration: {
      p50: 95000,
      p90: 110000,
      p99: 150000,
    },
    total_estimated_cost: 1.85,
    trace_count: 150,
    created_at: "2026-01-04T14:20:00Z",
    last_updated_at: "2026-01-04T15:30:00Z",
    metadata: {
      provider: "Anthropic",
      model_name: "claude-3-5-sonnet",
      temperature: 1.0,
      max_tokens: 1000,
    },
    comments: [],
    prompt_versions: [],
  },
  {
    id: "exp-4",
    name: "Gemini Pro 1.5",
    dataset_id: "dataset-1",
    dataset_name: "Customer Support Dataset",
    type: EXPERIMENT_TYPE.REGULAR,
    status: "completed",
    feedback_scores: [
      { name: "accuracy", value: 0.85 },
      { name: "hallucination_rate", value: 0.15 },
      { name: "relevance", value: 0.86 },
      { name: "helpfulness", value: 0.87 },
    ],
    experiment_scores: [
      { name: "mean_score", value: 0.86 },
    ],
    duration: {
      p50: 82000,
      p90: 100000,
      p99: 130000,
    },
    total_estimated_cost: 0.95,
    trace_count: 150,
    created_at: "2026-01-04T11:00:00Z",
    last_updated_at: "2026-01-04T12:15:00Z",
    metadata: {
      provider: "Google",
      model_name: "gemini-pro-1.5",
      temperature: 0.7,
      max_tokens: 1000,
    },
    comments: [],
    prompt_versions: [],
  },
  {
    id: "exp-5",
    name: "GPT-3.5 Turbo Baseline",
    dataset_id: "dataset-1",
    dataset_name: "Customer Support Dataset",
    type: EXPERIMENT_TYPE.REGULAR,
    status: "completed",
    feedback_scores: [
      { name: "accuracy", value: 0.78 },
      { name: "hallucination_rate", value: 0.22 },
      { name: "relevance", value: 0.81 },
      { name: "helpfulness", value: 0.80 },
    ],
    experiment_scores: [
      { name: "mean_score", value: 0.80 },
    ],
    duration: {
      p50: 65000,
      p90: 80000,
      p99: 110000,
    },
    total_estimated_cost: 0.45,
    trace_count: 150,
    created_at: "2026-01-03T16:30:00Z",
    last_updated_at: "2026-01-03T17:20:00Z",
    metadata: {
      provider: "OpenAI",
      model_name: "gpt-3.5-turbo",
      temperature: 0.7,
      max_tokens: 1000,
    },
    comments: [],
    prompt_versions: [],
  },
  {
    id: "exp-6",
    name: "Llama 3.1 70B Fine-tuned",
    dataset_id: "dataset-1",
    dataset_name: "Customer Support Dataset",
    type: EXPERIMENT_TYPE.REGULAR,
    status: "completed",
    feedback_scores: [
      { name: "accuracy", value: 0.87 },
      { name: "hallucination_rate", value: 0.13 },
      { name: "relevance", value: 0.88 },
      { name: "helpfulness", value: 0.85 },
    ],
    experiment_scores: [
      { name: "mean_score", value: 0.87 },
    ],
    duration: {
      p50: 145000,
      p90: 170000,
      p99: 220000,
    },
    total_estimated_cost: 3.20,
    trace_count: 150,
    created_at: "2026-01-06T09:00:00Z",
    last_updated_at: "2026-01-06T11:25:00Z",
    metadata: {
      provider: "Meta",
      model_name: "llama-3.1-70b",
      temperature: 0.5,
      max_tokens: 1000,
      fine_tuned: true,
    },
    comments: [],
    prompt_versions: [],
  },
  {
    id: "exp-7",
    name: "GPT-4 with RAG",
    dataset_id: "dataset-1",
    dataset_name: "Customer Support Dataset",
    type: EXPERIMENT_TYPE.REGULAR,
    status: "completed",
    feedback_scores: [
      { name: "accuracy", value: 0.97 },
      { name: "hallucination_rate", value: 0.03 },
      { name: "relevance", value: 0.95 },
      { name: "helpfulness", value: 0.94 },
    ],
    experiment_scores: [
      { name: "mean_score", value: 0.96 },
    ],
    duration: {
      p50: 185000,
      p90: 220000,
      p99: 280000,
    },
    total_estimated_cost: 4.75,
    trace_count: 150,
    created_at: "2026-01-07T07:15:00Z",
    last_updated_at: "2026-01-07T10:20:00Z",
    metadata: {
      provider: "OpenAI",
      model_name: "gpt-4",
      temperature: 0.3,
      max_tokens: 1500,
      rag_enabled: true,
    },
    comments: [],
    prompt_versions: [],
  },
  {
    id: "exp-8",
    name: "Mixtral 8x7B",
    dataset_id: "dataset-1",
    dataset_name: "Customer Support Dataset",
    type: EXPERIMENT_TYPE.REGULAR,
    status: "completed",
    feedback_scores: [
      { name: "accuracy", value: 0.83 },
      { name: "hallucination_rate", value: 0.17 },
      { name: "relevance", value: 0.84 },
      { name: "helpfulness", value: 0.82 },
    ],
    experiment_scores: [
      { name: "mean_score", value: 0.83 },
    ],
    duration: {
      p50: 72000,
      p90: 90000,
      p99: 120000,
    },
    total_estimated_cost: 0.65,
    trace_count: 150,
    created_at: "2026-01-03T13:45:00Z",
    last_updated_at: "2026-01-03T14:50:00Z",
    metadata: {
      provider: "Mistral",
      model_name: "mixtral-8x7b",
      temperature: 0.7,
      max_tokens: 1000,
    },
    comments: [],
    prompt_versions: [],
  },
];

/**
 * Available metric options for selection
 */
export const availableMetrics = [
  "accuracy",
  "hallucination_rate",
  "relevance",
  "helpfulness",
  "mean_score",
  "duration",
  "cost",
  "trace_count",
];

/**
 * Metric display names for better UX
 */
export const metricDisplayNames: Record<string, string> = {
  accuracy: "Accuracy",
  hallucination_rate: "Hallucination Rate",
  relevance: "Relevance",
  helpfulness: "Helpfulness",
  mean_score: "Mean Score",
  duration: "Duration (s)",
  cost: "Cost ($)",
  trace_count: "Trace Count",
};

/**
 * Default sort direction per metric type
 * true = ascending, false = descending
 */
export const defaultSortDirections: Record<string, boolean> = {
  accuracy: false, // Higher is better
  hallucination_rate: true, // Lower is better
  relevance: false,
  helpfulness: false,
  mean_score: false,
  duration: true, // Lower is better
  cost: true, // Lower is better
  trace_count: false,
};

