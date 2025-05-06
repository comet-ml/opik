import { UseQueryOptions } from "@tanstack/react-query";
import axios from "axios";

export const BASE_API_URL = import.meta.env.VITE_BASE_API_URL || "/api";
const axiosInstance = axios.create({
  baseURL: BASE_API_URL,
});

axiosInstance.defaults.withCredentials = true;

export const CODE_EXECUTOR_SERVICE_URL = import.meta.env
  .VITE_GET_STARTED_API_URL;
export const FEATURE_TOGGLES_REST_ENDPOINT = "/v1/private/toggles/";
export const PROJECTS_REST_ENDPOINT = "/v1/private/projects/";
export const DATASETS_REST_ENDPOINT = "/v1/private/datasets/";
export const EXPERIMENTS_REST_ENDPOINT = "/v1/private/experiments/";
export const FEEDBACK_DEFINITIONS_REST_ENDPOINT =
  "/v1/private/feedback-definitions/";
export const TRACES_REST_ENDPOINT = "/v1/private/traces/";
export const SPANS_REST_ENDPOINT = "/v1/private/spans/";
export const PROMPTS_REST_ENDPOINT = "/v1/private/prompts/";
export const PROVIDER_KEYS_REST_ENDPOINT = "/v1/private/llm-provider-key/";
export const AUTOMATIONS_REST_ENDPOINT = "/v1/private/automations/";
export const ATTACHMENTS_REST_ENDPOINT = "/v1/private/attachment/";
export const OPTIMIZATIONS_REST_ENDPOINT = "/v1/private/optimizations/";

export const COMPARE_EXPERIMENTS_KEY = "compare-experiments";
export const SPANS_KEY = "spans";
export const TRACES_KEY = "traces";
export const TRACE_KEY = "trace";
export const THREADS_KEY = "threads";
export const PROVIDERS_KEYS_KEY = "provider-keys";
export const AUTOMATIONS_KEY = "automations";
export const PROJECTS_KEY = "projects";
export const PROJECT_STATISTICS_KEY = "project-statistics";
export const OPTIMIZATIONS_KEY = "optimizations";
export const OPTIMIZATION_KEY = "optimization";

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
