import { UseQueryOptions } from "@tanstack/react-query";
import axios from "axios";

export const BASE_API_URL = import.meta.env.VITE_BASE_API_URL || "/api";
export const BASE_OPIK_AI_URL = import.meta.env.VITE_BASE_OPIK_AI_URL || "";
const axiosInstance = axios.create({
  baseURL: BASE_API_URL,
});

axiosInstance.defaults.withCredentials = true;

export const CODE_EXECUTOR_SERVICE_URL = import.meta.env
  .VITE_GET_STARTED_API_URL;
export const FEATURE_TOGGLES_REST_ENDPOINT = "/v1/private/toggles/";
export const WELCOME_WIZARD_REST_ENDPOINT = "/v1/private/welcome-wizard";
export const PROJECTS_REST_ENDPOINT = "/v1/private/projects/";
export const DATASETS_REST_ENDPOINT = "/v1/private/datasets/";
export const EXPERIMENTS_REST_ENDPOINT = "/v1/private/experiments/";
export const FEEDBACK_DEFINITIONS_REST_ENDPOINT =
  "/v1/private/feedback-definitions/";
export const TRACES_REST_ENDPOINT = "/v1/private/traces/";
export const SPANS_REST_ENDPOINT = "/v1/private/spans/";
export const PROMPTS_REST_ENDPOINT = "/v1/private/prompts/";
export const PROVIDER_KEYS_REST_ENDPOINT = "/v1/private/llm-provider-key/";
export const OLLAMA_REST_ENDPOINT = "/v1/private/ollama/";
export const ALERTS_REST_ENDPOINT = "/v1/private/alerts/";
export const AUTOMATIONS_REST_ENDPOINT = "/v1/private/automations/";
export const ATTACHMENTS_REST_ENDPOINT = "/v1/private/attachment/";
export const OPTIMIZATIONS_REST_ENDPOINT = "/v1/private/optimizations/";
export const OPTIMIZATIONS_STUDIO_REST_ENDPOINT =
  "/v1/private/optimizations/studio/";
export const ANNOTATION_QUEUES_REST_ENDPOINT = "/v1/private/annotation-queues/";
export const WORKSPACES_REST_ENDPOINT = "/v1/private/workspaces/";
export const WORKSPACE_CONFIG_REST_ENDPOINT =
  "/v1/private/workspaces/configurations/";
export const TRACE_ANALYZER_REST_ENDPOINT = "/trace-analyzer/session/";
export const PLAYGROUND_EVALUATION_REST_ENDPOINT =
  "/v1/private/playground/evaluations/";
export const DASHBOARDS_REST_ENDPOINT = "/v1/private/dashboards/";
export const RUNNERS_REST_ENDPOINT = "/v1/private/runners/";

export const COMPARE_EXPERIMENTS_KEY = "compare-experiments";
export const SPANS_KEY = "spans";
export const TRACES_KEY = "traces";
export const TRACE_KEY = "trace";
export const THREADS_KEY = "threads";
export const PROVIDERS_KEYS_KEY = "provider-keys";
export const ALERTS_KEY = "alerts";
export const AUTOMATIONS_KEY = "automations";
export const PROJECTS_KEY = "projects";
export const PROJECT_STATISTICS_KEY = "project-statistics";
export const OPTIMIZATIONS_KEY = "optimizations";
export const OPTIMIZATION_KEY = "optimization";
export const ANNOTATION_QUEUES_KEY = "annotation-queues";
export const ANNOTATION_QUEUE_KEY = "annotation-queue";
export const WORKSPACE_CONFIG_KEY = "workspace-config";
export const TRACE_AI_ASSISTANT_KEY = "trace-analyzer-history";
export const DASHBOARDS_KEY = "dashboards";
export const DASHBOARD_KEY = "dashboard";
export const RUNNERS_KEY = "runners";
export const RUNNER_JOBS_KEY = "runner-jobs";
export const RUNNER_JOB_LOGS_KEY = "runner-job-logs";

// stats for feedback
export const STATS_COMET_ENDPOINT = "https://stats.comet.com/notify/event/";
export const STATS_ANONYMOUS_ID = "guest";

export type QueryConfig<TQueryFnData, TData = TQueryFnData> = Omit<
  UseQueryOptions<
    TQueryFnData,
    Error,
    TData,
    [string, Record<string, unknown>, ...string[]]
  >,
  "queryKey" | "queryFn"
>;

export default axiosInstance;
