import { UseQueryOptions } from "@tanstack/react-query";
import axios from "axios";
import { v4 as uuidv4 } from "uuid";

export const BASE_API_URL = import.meta.env.VITE_BASE_API_URL || "/api";
export const BASE_OPIK_AI_URL = import.meta.env.VITE_BASE_OPIK_AI_URL || "";
const axiosInstance = axios.create({
  baseURL: BASE_API_URL,
});

axiosInstance.defaults.withCredentials = true;

// Lets an installation count the distinct browsers using it. Random, so it identifies no one.
const BROWSER_ID_KEY = "opik-browser-id";
const getBrowserId = () => {
  try {
    const stored = localStorage.getItem(BROWSER_ID_KEY);
    if (stored) return stored;
    const id = uuidv4();
    localStorage.setItem(BROWSER_ID_KEY, id);
    return id;
  } catch {
    // Storage blocked (private mode, disabled site data): count this page load on its own.
    return uuidv4();
  }
};
const browserId = getBrowserId();
// Opik backend only: other services reached through this instance (BASE_OPIK_AI_URL)
// may be cross-origin, and an unknown header would fail their CORS preflight.
axiosInstance.interceptors.request.use((config) => {
  const isOpikBackend =
    (config.baseURL ?? BASE_API_URL) === BASE_API_URL &&
    !/^https?:\/\//.test(config.url ?? "");
  if (isOpikBackend) {
    config.headers.set("X-Opik-Browser-Id", browserId);
  }
  return config;
});

export const CODE_EXECUTOR_SERVICE_URL = import.meta.env
  .VITE_GET_STARTED_API_URL;
export const FEATURE_TOGGLES_REST_ENDPOINT = "/v1/private/toggles/";
export const WELCOME_WIZARD_REST_ENDPOINT = "/v1/private/welcome-wizard";
export const DEPLOYMENT_VERSIONS_REST_ENDPOINT =
  "/v1/private/deployment/versions";
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
export const EXPERIMENT_EXECUTION_REST_ENDPOINT =
  "/v1/private/experiments/execute";
export const DASHBOARDS_REST_ENDPOINT = "/v1/private/dashboards/";
export const INSIGHTS_VIEWS_REST_ENDPOINT = "/v1/private/insights-views/";
export const AGENT_CONFIGS_REST_ENDPOINT = "/v1/private/agent-configs/";
export const LOCAL_RUNNERS_REST_ENDPOINT = "/v1/private/local-runners/";
export const LLM_MODELS_REST_ENDPOINT = "/v1/private/llm/models";
export const ENVIRONMENTS_REST_ENDPOINT = "/v1/private/environments/";
export const AI_SPEND_REST_ENDPOINT = "/v1/private/ai-spend/";
export const AGENT_INSIGHTS_REST_ENDPOINT = "/v1/private/agent-insights/";
// OAuth endpoints are public (no /v1/private prefix); served by opik-backend's MCP OAuth AS.
export const OAUTH_REST_ENDPOINT = "/oauth/";
export const OAUTH_AUTHORIZE_CONTEXT_KEY = "oauth-authorize-context";

export const COMPARE_EXPERIMENTS_KEY = "compare-experiments";
export const SPANS_KEY = "spans";
export const SPAN_KEY = "span";
export const TRACES_KEY = "traces";
export const TRACE_KEY = "trace";
export const THREADS_KEY = "threads";
export const PROVIDERS_KEYS_KEY = "provider-keys";
export const ALERTS_KEY = "alerts";
export const AUTOMATIONS_KEY = "automations";
export const PROJECTS_KEY = "projects";
export const PROJECT_KEY = "project";
export const PROJECT_STATISTICS_KEY = "project-statistics";
export const OPTIMIZATIONS_KEY = "optimizations";
export const OPTIMIZATION_KEY = "optimization";
export const ANNOTATION_QUEUES_KEY = "annotation-queues";
export const ANNOTATION_QUEUE_KEY = "annotation-queue";
export const WORKSPACE_CONFIG_KEY = "workspace-config";
export const TRACE_AI_ASSISTANT_KEY = "trace-analyzer-history";
export const DASHBOARDS_KEY = "dashboards";
export const DASHBOARD_KEY = "dashboard";
export const INSIGHTS_VIEWS_KEY = "insights-views";
export const INSIGHTS_VIEW_KEY = "insights-view";
export const AGENT_CONFIGS_KEY = "agent-configs";
export const AGENT_SANDBOX_KEY = "agent-sandbox";
export const LLM_MODELS_KEY = "llm-models";
export const ENVIRONMENTS_KEY = "environments";
export const ENVIRONMENT_KEY = "environment";
export const AGENT_INSIGHTS_ISSUES_KEY = "agent-insights-issues";
export const AGENT_INSIGHTS_ISSUE_KEY = "agent-insights-issue";
export const AGENT_INSIGHTS_JOB_KEY = "agent-insights-job";
export const OLLIE_CREDITS_KEY = "ollie-credits";

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
