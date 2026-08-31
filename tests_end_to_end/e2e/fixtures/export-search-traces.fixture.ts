import { test as baseTest } from './annotation-queue-cleanup.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

export interface ExportSearchTraceRef {
  id: string;
  name: string;
}

export interface ExportSearchTracesRef {
  /** Named `<namespace>-ExportAlpha-N` — mixed case, three of them. */
  alpha: ExportSearchTraceRef[];
  /** Named `<namespace>-exportbeta-N` — all lower case, two of them. */
  beta: ExportSearchTraceRef[];
  /** Every seeded trace, whichever group it belongs to. */
  all: ExportSearchTraceRef[];
}

export interface ExportSearchTracesFixtures {
  exportSearchTraces: ExportSearchTracesRef;
}

const ALPHA_COUNT = 3;
const BETA_COUNT = 2;

/**
 * Two groups of traces in one project whose names differ by case, so a search
 * term can be varied in case and padding while its expected result set stays
 * known.
 *
 * The counts differ (3 vs 2) deliberately: a search that returned the wrong
 * group could not pass on the row count alone.
 *
 * Inputs and outputs deliberately avoid the searchable name fragments — "Search
 * by anything" reads those fields too, so a payload echoing the name would make
 * a term match for a reason the spec is not about.
 *
 * Teardown deletes the traces explicitly. `ProjectService.delete` removes only
 * the project row, so traces do NOT cascade with the `project` fixture, and the
 * run-prefix sweep in `global-teardown.ts` does not know about them either.
 */
export const test = baseTest.extend<ExportSearchTracesFixtures>({
  exportSearchTraces: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    // Seeded through the nested-trace route with no spans rather than through
    // plain `createTrace`. Both create one trace, but the nested route waits out
    // a throttled write (its client timeout is 150s, against `createTrace`'s
    // 30s) — and five sequential seeds on a shared cloud workspace is exactly
    // where a 429 back-off turns into a red test that has nothing to say.
    const seed = async (name: string, index: number): Promise<ExportSearchTraceRef> => {
      const created = await sdkClient.python.createNestedTrace({
        project_name: project.name,
        name,
        input: { note: `payload-in-${index}` },
        output: { note: `payload-out-${index}` },
        spans: [],
      });
      return { id: created.id, name: created.name };
    };

    const alpha: ExportSearchTraceRef[] = [];
    for (let i = 1; i <= ALPHA_COUNT; i++) {
      alpha.push(await seed(`${testNamespace}-ExportAlpha-${i}`, i));
    }
    const beta: ExportSearchTraceRef[] = [];
    for (let i = 1; i <= BETA_COUNT; i++) {
      beta.push(await seed(`${testNamespace}-exportbeta-${i}`, i));
    }

    const ref: ExportSearchTracesRef = { alpha, beta, all: [...alpha, ...beta] };
    await testInfo.attach('opik.exportSearchTraces', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteTraces(ref.all.map((t) => t.id));
      } catch (err) {
        console.warn(`[exportSearchTraces fixture] delete warning for ${testNamespace}:`, err);
      }
    }
  },
});

export { expect } from './annotation-queue-cleanup.fixture';
