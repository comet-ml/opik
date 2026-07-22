import { test as baseTest } from './annotation-queue.fixture';

export interface ExplainTraceRef {
  id: string;
  name: string;
  errorType: string;
  /** Seeded trace duration in seconds; null means left unset (renders as Duration "NA"). */
  durationSeconds: number | null;
  /** Seeded LLM-span cost; null means no cost-bearing span (renders as Cost "-"). */
  cost: number | null;
  /**
   * Source of a case-insensitive RegExp expected to appear in Ollie's error
   * explanation for this trace. Ollie's phrasing is non-deterministic (see
   * ollie-explain.spec.ts), so this is anchored to concrete details of the
   * seeded error (exception type / message) rather than generic
   * error-adjacent vocabulary ("error", "fail", ...) — those turned out to be
   * skippable depending on phrasing (e.g. a rate-limit explanation that never
   * says "fail" or "exceed"), while the concrete subject (rate limit,
   * document store, timeout, permissions, faiss) reliably recurs because
   * Ollie is grounded in that seeded content.
   */
  errorKeywordSource: string;
}

export interface ExplainTracesFixtures {
  explainTraces: ExplainTraceRef[];
}

/**
 * Five traces shaped for the Ollie "Explain" cell feature: each has a distinct
 * trace-level error, and distinct duration/cost, with one trace leaving both
 * duration and cost unset so the explain button's N/A path (Duration "NA",
 * Cost "-") gets covered too — per `explainTargets.ts`, those cells stay
 * explainable even at N/A, unlike the error cell which vetoes on no error.
 */
const SEEDS: Array<{
  suffix: string;
  errorType: string;
  errorMessage: string;
  /** RegExp source (case-insensitive) expected in Ollie's explanation of this error. */
  errorKeyword: string;
  durationSeconds: number | null;
  cost: number | null;
}> = [
  {
    suffix: 'rate-limit',
    errorType: 'RuntimeError',
    errorMessage: 'Model returned status 429: rate limit exceeded',
    errorKeyword: 'rate.?limit',
    durationSeconds: 2,
    cost: 0.0005,
  },
  {
    suffix: 'missing-context',
    errorType: 'ValueError',
    errorMessage: 'Missing required context: document store unavailable',
    errorKeyword: 'context|document store',
    durationSeconds: 15,
    cost: 0.02,
  },
  {
    suffix: 'tool-timeout',
    errorType: 'TimeoutError',
    errorMessage: 'Tool call timed out after 30 s',
    errorKeyword: 'timeout',
    durationSeconds: 60,
    cost: 0.5,
  },
  {
    suffix: 'auth-failure',
    errorType: 'PermissionError',
    errorMessage: 'API key does not have access to model claude-3-opus',
    errorKeyword: 'permission|access|api key',
    durationSeconds: 180,
    cost: 2.0,
  },
  {
    suffix: 'quota-exceeded',
    errorType: 'ImportError',
    errorMessage: "Required dependency 'faiss' is not installed",
    errorKeyword: 'faiss|install|depend',
    durationSeconds: null,
    cost: null,
  },
];

export const test = baseTest.extend<ExplainTracesFixtures>({
  explainTraces: async ({ sdkClient, project, testNamespace }, use, testInfo) => {
    const refs: ExplainTraceRef[] = [];
    for (const seed of SEEDS) {
      const name = `${testNamespace}-explain-${seed.suffix}`;
      const spans =
        seed.cost === null
          ? []
          : [
              {
                name: 'llm-call',
                type: 'llm' as const,
                model: 'gpt-4o',
                provider: 'openai',
                input: { prompt: 'seed prompt' },
                output: { completion: 'seed completion' },
                usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 },
                total_cost: seed.cost,
              },
            ];
      const created = await sdkClient.python.createNestedTrace({
        project_name: project.name,
        name,
        input: { user: `trigger for ${seed.suffix}` },
        tags: ['explain', seed.suffix],
        error_info: {
          exception_type: seed.errorType,
          message: seed.errorMessage,
        },
        duration_seconds: seed.durationSeconds ?? undefined,
        spans,
      });
      refs.push({
        id: created.id,
        name: created.name,
        errorType: seed.errorType,
        durationSeconds: seed.durationSeconds,
        cost: seed.cost,
        errorKeywordSource: seed.errorKeyword,
      });
    }

    await testInfo.attach('opik.explainTraces', {
      body: JSON.stringify(refs, null, 2),
      contentType: 'application/json',
    });

    await use(refs);
    // No explicit teardown — the project fixture's deleteProject cascades.
  },
});

export { expect } from './annotation-queue.fixture';
