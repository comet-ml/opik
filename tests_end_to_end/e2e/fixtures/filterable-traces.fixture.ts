import { test as baseTest } from './explain-traces.fixture';

export interface FilterableTraceRef {
  id: string;
  name: string;
  tags: string[];
  hasError: boolean;
  /** Value of the `relevance` feedback score, or null when the trace carries none. */
  relevance: number | null;
}

export interface FilterableTracesFixtures {
  filterableTraces: {
    all: FilterableTraceRef[];
    /** Tag carried by exactly two of the three traces. */
    sharedTag: string;
    /** Feedback score name carried by exactly two of the three traces. */
    scoreName: string;
    /** Threshold that admits only the high-scoring trace. */
    scoreThreshold: number;
  };
}

const SHARED_TAG = 'prod';
const SCORE_NAME = 'relevance';
const SCORE_THRESHOLD = 0.5;

/**
 * Three traces whose attributes are deliberately staggered so each filter under
 * test narrows to a *different*, non-trivial subset — a filter that silently
 * ignored its input would return all three and fail every assertion:
 *
 *   tag "prod"        -> alpha + beta   (2 of 3)
 *   with errors       -> alpha          (1 of 3)
 *   relevance >= 0.5  -> alpha          (1 of 3, beta scores 0.2 and is excluded)
 *
 * Beta is the discriminator for the score filter: it carries the same score
 * *name* as alpha but a value below the threshold, so a filter that matched on
 * key presence alone rather than comparing the value would wrongly return it.
 */
const SEEDS: Array<{
  suffix: string;
  tags: string[];
  error: { exception_type: string; message: string } | null;
  relevance: number | null;
}> = [
  {
    suffix: 'alpha',
    tags: [SHARED_TAG, 'alpha'],
    error: { exception_type: 'ValueError', message: 'alpha failed to resolve context' },
    relevance: 0.9,
  },
  {
    suffix: 'beta',
    tags: [SHARED_TAG],
    error: null,
    relevance: 0.2,
  },
  {
    suffix: 'gamma',
    tags: ['staging'],
    error: null,
    relevance: null,
  },
];

export const test = baseTest.extend<FilterableTracesFixtures>({
  filterableTraces: async ({ sdkClient, project, testNamespace }, use, testInfo) => {
    const all: FilterableTraceRef[] = [];
    for (const seed of SEEDS) {
      const name = `${testNamespace}-filter-${seed.suffix}`;
      const created = await sdkClient.python.createNestedTrace({
        project_name: project.name,
        name,
        input: { query: `question from ${seed.suffix}` },
        output: { answer: `answer from ${seed.suffix}` },
        tags: seed.tags,
        feedback_scores:
          seed.relevance === null ? undefined : [{ name: SCORE_NAME, value: seed.relevance }],
        error_info: seed.error ?? undefined,
        spans: [],
      });
      all.push({
        id: created.id,
        name: created.name,
        tags: seed.tags,
        hasError: seed.error !== null,
        relevance: seed.relevance,
      });
    }

    await testInfo.attach('opik.filterableTraces', {
      body: JSON.stringify(all, null, 2),
      contentType: 'application/json',
    });

    await use({
      all,
      sharedTag: SHARED_TAG,
      scoreName: SCORE_NAME,
      scoreThreshold: SCORE_THRESHOLD,
    });
    // No explicit teardown — the project fixture's deleteProject cascades.
  },
});

export { expect } from './explain-traces.fixture';
