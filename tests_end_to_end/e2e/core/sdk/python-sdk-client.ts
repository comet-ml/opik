export interface PythonSdkClient {
  createProject(args: { name: string; workspace?: string }): Promise<{ id: string; name: string }>;
  createTrace(args: {
    project_name: string;
    name: string;
    input: string;
    output: string;
    thread_id?: string;
    workspace?: string;
  }): Promise<{ id: string; name: string; project_id: string }>;
  createNestedTrace(args: {
    project_name: string;
    name: string;
    input?: Record<string, unknown>;
    output?: Record<string, unknown>;
    metadata?: Record<string, unknown>;
    tags?: string[];
    thread_id?: string;
    feedback_scores?: Array<{ name: string; value: number; reason?: string }>;
    error_info?: { exception_type: string; message: string; traceback?: string };
    duration_seconds?: number;
    spans: Array<{
      name: string;
      type?: 'general' | 'llm' | 'tool';
      input?: Record<string, unknown>;
      output?: Record<string, unknown>;
      metadata?: Record<string, unknown>;
      model?: string;
      provider?: string;
      usage?: { prompt_tokens: number; completion_tokens: number; total_tokens: number };
      total_cost?: number;
      parent_index?: number;
    }>;
    workspace?: string;
  }): Promise<{ id: string; name: string; project_id: string; span_count: number }>;
  createFeedbackDefinition(args: {
    name: string;
    min?: number;
    max?: number;
    workspace?: string;
  }): Promise<{ id: string; name: string }>;
  // Workspace is resolved from the bridge's env (OPIK_WORKSPACE), the same as
  // createFeedbackDefinition, so both operate on one workspace per run.
  deleteFeedbackDefinition(args: { id: string }): Promise<void>;
  createDataset(args: {
    name: string;
    project_name: string;
    description?: string;
    items?: Array<Record<string, unknown>>;
    workspace?: string;
  }): Promise<{ id: string; name: string }>;
  evaluateExperiment(args: {
    project_name: string;
    dataset_name: string;
    experiment_name: string;
    items: Array<Record<string, unknown>>;
    dataset_description?: string;
    workspace?: string;
  }): Promise<{
    experiment_id: string;
    experiment_name: string;
    dataset_id: string;
    item_count: number;
    scored_item_count: number;
    scores: Array<{
      dataset_item_id: string;
      input: string;
      expected_output: string;
      task_output: string;
      score_name: string;
      score_value: number;
    }>;
  }>;
  /**
   * Evaluate one dataset twice with a passing metric and a permanently-failing
   * one: once at evaluate()'s default error tolerance (expected to abort) and
   * once at ALL_SCORING_ERRORS (expected to complete with the failure recorded
   * per item and kept out of the aggregate).
   */
  scoringErrorSeed(args: {
    project_name: string;
    dataset_name: string;
    items: Array<Record<string, unknown>>;
    aborting_experiment_name: string;
    tolerated_experiment_name: string;
    dataset_description?: string;
    workspace?: string;
  }): Promise<{
    dataset_id: string;
    dataset_name: string;
    item_count: number;
    passing_metric_name: string;
    failing_metric_name: string;
    aborting: {
      aborted: boolean;
      exception_type: string | null;
      message: string | null;
      experiment_name: string;
    };
    tolerated: {
      experiment_id: string;
      experiment_name: string;
      aggregate_score_names: string[];
      aggregate_means: Record<string, number>;
      item_results: Array<{
        dataset_item_id: string;
        trace_id: string | null;
        metric_name: string;
        value: number;
        scoring_failed: boolean;
        error_exception_type: string | null;
      }>;
    };
  }>;
  compareSeed(args: {
    project_name: string;
    dataset_name: string;
    items: Array<{ input: string; expected_output: string }>;
    experiments: Array<{ experiment_name: string; task_outputs: string[] }>;
    dataset_description?: string;
    workspace?: string;
  }): Promise<{
    dataset_id: string;
    dataset_name: string;
    item_count: number;
    experiments: Array<{
      experiment_id: string;
      experiment_name: string;
      scores: Array<{
        dataset_item_id: string;
        input: string;
        expected_output: string;
        task_output: string;
        score_name: string;
        score_value: number;
      }>;
    }>;
  }>;
  createTextPrompt(args: {
    name: string;
    prompt: string;
    description?: string;
    project_name?: string;
    workspace?: string;
  }): Promise<{ id: string; name: string }>;
  createChatPrompt(args: {
    name: string;
    messages: Array<{ role: string; content: string }>;
    description?: string;
    project_name?: string;
    workspace?: string;
  }): Promise<{ id: string; name: string }>;
  createTestSuite(args: {
    name: string;
    project_name: string;
    description?: string;
    global_assertions?: string[];
    runs_per_item?: number;
    pass_threshold?: number;
    items?: Array<{
      data: Record<string, unknown>;
      assertions?: string[];
      description?: string;
    }>;
    workspace?: string;
  }): Promise<{ id: string; name: string }>;
  insertTestSuiteItems(args: {
    suite_name: string;
    project_name: string;
    items: Array<{
      data: Record<string, unknown>;
      assertions?: string[];
      description?: string;
    }>;
    workspace?: string;
  }): Promise<{ suite_id: string; inserted: number }>;
  runTestSuite(args: {
    suite_name: string;
    project_name: string;
    task_output: string;
    experiment_name: string;
    judge_model?: string;
    workspace?: string;
  }): Promise<{
    experiment_id: string | null;
    experiment_name: string | null;
    pass_rate: number | null;
    items_passed: number;
    items_failed: number;
    items_total: number;
  }>;
  createAnnotationQueue(args: {
    project_name: string;
    name: string;
    trace_ids: string[];
    feedback_definition_names?: string[];
    workspace?: string;
  }): Promise<{ id: string; name: string }>;
}

export class PythonSdkBridgeError extends Error {
  readonly status: number;
  readonly endpoint: string;
  readonly detail: unknown;

  constructor(args: { status: number; endpoint: string; detail: unknown; message: string }) {
    super(args.message);
    this.name = 'PythonSdkBridgeError';
    this.status = args.status;
    this.endpoint = args.endpoint;
    this.detail = args.detail;
  }
}

const DEFAULT_BRIDGE_URL = 'http://localhost:5175';
const REQUEST_TIMEOUT_MS = 30_000;
/**
 * Routes that run more than one evaluate() per call need longer than the
 * default: each evaluation is a full task + scoring pass against the backend,
 * and 30s is a per-call budget sized for single-shot seeds.
 */
const MULTI_EVALUATION_TIMEOUT_MS = 120_000;

export function makePythonSdkClient(opts: { bridgeUrl?: string } = {}): PythonSdkClient {
  const bridgeUrl = opts.bridgeUrl ?? process.env.OPIK_SDK_DRIVER_URL ?? DEFAULT_BRIDGE_URL;

  async function request<TResponse>(
    method: 'GET' | 'POST' | 'DELETE',
    path: string,
    body?: unknown,
    timeoutMs: number = REQUEST_TIMEOUT_MS,
  ): Promise<TResponse> {
    const endpoint = `${method} ${path}`;
    const ctrl = new AbortController();
    const timer = setTimeout(() => ctrl.abort(), timeoutMs);
    try {
      const headers: Record<string, string> = {};
      if (body !== undefined) headers['content-type'] = 'application/json';
      // Read OPIK_API_KEY at request time so the minted key from globalSetup
      // is picked up after the bridge has already spawned.
      const apiKey = process.env.OPIK_API_KEY;
      if (apiKey) headers['X-Opik-Api-Key'] = apiKey;
      let res: Response;
      try {
        res = await fetch(`${bridgeUrl}${path}`, {
          method,
          headers: Object.keys(headers).length > 0 ? headers : undefined,
          body: body !== undefined ? JSON.stringify(body) : undefined,
          signal: ctrl.signal,
        });
      } catch (err) {
        if (ctrl.signal.aborted) {
          throw new PythonSdkBridgeError({
            status: 0,
            endpoint,
            detail: 'client-timeout',
            message: `opik-sdk-driver ${endpoint} aborted after ${timeoutMs}ms (client-side timeout — the bridge or backend did not respond in time)`,
          });
        }
        throw err;
      }
      if (res.ok) {
        return (await res.json()) as TResponse;
      }
      const text = await res.text();
      let detail: unknown;
      try {
        detail = JSON.parse(text);
      } catch {
        detail = text;
      }
      const summary =
        typeof detail === 'object' && detail !== null && 'detail' in detail
          ? JSON.stringify((detail as { detail: unknown }).detail)
          : text.slice(0, 200);
      throw new PythonSdkBridgeError({
        status: res.status,
        endpoint,
        detail,
        message: `opik-sdk-driver ${endpoint} -> ${res.status}: ${summary}`,
      });
    } finally {
      clearTimeout(timer);
    }
  }

  return {
    async createProject({ name, workspace }) {
      return request<{ id: string; name: string }>('POST', '/projects', { name, workspace });
    },
    async createTrace(args) {
      return request<{ id: string; name: string; project_id: string }>('POST', '/traces', args);
    },
    async createNestedTrace(args) {
      return request<{ id: string; name: string; project_id: string; span_count: number }>(
        'POST',
        '/traces/nested',
        args,
      );
    },
    async createFeedbackDefinition(args) {
      return request<{ id: string; name: string }>('POST', '/feedback-definitions', args);
    },
    async deleteFeedbackDefinition({ id }) {
      await request<{ deleted: boolean }>('DELETE', `/feedback-definitions/${id}`);
    },
    async createDataset(args) {
      return request<{ id: string; name: string }>('POST', '/datasets', args);
    },
    async scoringErrorSeed(args) {
      return request<{
        dataset_id: string;
        dataset_name: string;
        item_count: number;
        passing_metric_name: string;
        failing_metric_name: string;
        aborting: {
          aborted: boolean;
          exception_type: string | null;
          message: string | null;
          experiment_name: string;
        };
        tolerated: {
          experiment_id: string;
          experiment_name: string;
          aggregate_score_names: string[];
          aggregate_means: Record<string, number>;
          item_results: Array<{
            dataset_item_id: string;
            trace_id: string | null;
            metric_name: string;
            value: number;
            scoring_failed: boolean;
            error_exception_type: string | null;
          }>;
        };
      }>('POST', '/experiments/scoring-error-seed', args, MULTI_EVALUATION_TIMEOUT_MS);
    },
    async compareSeed(args) {
      return request<{
        dataset_id: string;
        dataset_name: string;
        item_count: number;
        experiments: Array<{
          experiment_id: string;
          experiment_name: string;
          scores: Array<{
            dataset_item_id: string;
            input: string;
            expected_output: string;
            task_output: string;
            score_name: string;
            score_value: number;
          }>;
        }>;
      }>('POST', '/experiments/compare-seed', args);
    },
    async createTextPrompt(args) {
      return request<{ id: string; name: string }>('POST', '/prompts/text', args);
    },
    async createChatPrompt(args) {
      return request<{ id: string; name: string }>('POST', '/prompts/chat', args);
    },
    async evaluateExperiment(args) {
      return request<{
        experiment_id: string;
        experiment_name: string;
        dataset_id: string;
        item_count: number;
        scored_item_count: number;
        scores: Array<{
          dataset_item_id: string;
          input: string;
          expected_output: string;
          task_output: string;
          score_name: string;
          score_value: number;
        }>;
      }>('POST', '/experiments/evaluate', args);
    },
    async createTestSuite(args) {
      return request<{ id: string; name: string }>('POST', '/test-suites', args);
    },
    async insertTestSuiteItems(args) {
      return request<{ suite_id: string; inserted: number }>(
        'POST',
        '/test-suites/insert-items',
        args,
      );
    },
    async runTestSuite(args) {
      return request<{
        experiment_id: string | null;
        experiment_name: string | null;
        pass_rate: number | null;
        items_passed: number;
        items_failed: number;
        items_total: number;
      }>('POST', '/test-suites/run', args);
    },
    async createAnnotationQueue(args) {
      return request<{ id: string; name: string }>('POST', '/annotation-queues', args);
    },
  };
}
