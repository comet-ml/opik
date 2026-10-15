import { test as baseTest, expect } from './compare-prompt-versions.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type BackendFilter } from '../core/backend';

/**
 * The attribute values the quick filter is driven from. Distinct per pair and
 * chosen so no `contains` of one matches the other — that is what makes
 * "the table narrowed to alpha" a real assertion rather than an accident of
 * there being one row.
 */
export const QUICK_FILTER_SEED = {
  alpha: { tenant: 'tenant-alpha', component: 'retriever-alpha', provider: 'openai' },
  beta: { tenant: 'tenant-beta', component: 'retriever-beta', provider: 'anthropic' },
} as const;

/** The metadata key a trace carries that collides with the span provider column. */
export const TRACE_PROVIDER_METADATA_VALUE = 'trace-level-provider';

export interface QuickFilterPairRef {
  traceId: string;
  traceName: string;
  spanId: string;
  spanName: string;
}

export interface QuickFilterLogsRef {
  projectId: string;
  projectName: string;
  /** The pair every quick filter in these specs selects for. */
  alpha: QuickFilterPairRef;
  /** The negative control: never selected, and must be excluded by every filter. */
  beta: QuickFilterPairRef;
}

export interface QuickFilterLogsFixtures {
  quickFilterLogs: QuickFilterLogsRef;
}

/**
 * Two trace + child-span pairs whose attributes differ on every axis the Logs
 * quick filter can target (opik#8684, OPIK-8105).
 *
 * The shape is deliberate. The quick filter's regression is a SILENT one — the
 * filter used to land on the Traces table while the user had a span selected,
 * which produced a plausible but unrelated result set rather than an error. The
 * only way to see that is to have a second entity that a correctly-routed
 * filter must exclude, so the beta pair exists purely to be left out.
 *
 * Each span carries its provider twice: once in the dedicated `provider`
 * column, which is what the span quick filter targets, and once as a metadata
 * key of the same name, which is what the user actually clicks on. Each trace
 * carries a root `provider` metadata key too — traces have no provider column,
 * so that key must offer no filter action at all while its siblings do.
 *
 * Seeded over REST rather than through the SDK bridge because the span's
 * `provider` is a write field the bridge does not expose, and because the
 * metadata has to arrive exactly as written for the rendered YAML to be
 * predictable.
 *
 * The fixture proves its own discrimination against the API before any browser
 * opens: each of the three filter shapes the specs drive is queried directly
 * and must return exactly the alpha entity. A seed that silently failed to
 * differentiate would otherwise produce a UI assertion that cannot fail.
 *
 * Teardown deletes the two traces; their spans cascade with them. Nothing is
 * left for the run-prefix sweep, which only reaches traces six hours later and
 * would let two runs' alpha/beta pairs coexist in one project.
 *
 * Every write sits inside the `try`, so the `finally` covers them all — they
 * happen BEFORE `use()`, and the three discrimination `expect`s below throw on
 * a seed that failed to differentiate. Cleanup placed after `use()` is skipped
 * by exactly that, which leaves a half-seeded pair behind for six hours and
 * poisons the very next run's single-row assertions. Each id is collected as
 * its write succeeds.
 */
export const test = baseTest.extend<QuickFilterLogsFixtures>({
  quickFilterLogs: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    // Client-generated and collected before the write returns, so a POST that
    // commits server-side but loses its response still has something to delete.
    const traceIds: string[] = [];

    const seedPair = async (
      which: 'alpha' | 'beta',
    ): Promise<QuickFilterPairRef> => {
      const values = QUICK_FILTER_SEED[which];
      const traceId = uuid7();
      const traceName = `${testNamespace}-trace-${which}`;
      traceIds.push(traceId);
      await backendClient.createTraceWithSource({
        id: traceId,
        projectName: project.name,
        name: traceName,
        source: 'sdk',
        input: { question: `ask ${which}` },
        output: { answer: `answer ${which}` },
        metadata: {
          tenant: values.tenant,
          stage: 'ingest',
          // A trace has no provider column, so this key must render without a
          // filter action while `tenant` and `stage` beside it keep theirs.
          provider: TRACE_PROVIDER_METADATA_VALUE,
        },
      });

      const spanId = uuid7();
      const spanName = `${testNamespace}-span-${which}`;
      await backendClient.createSpan({
        id: spanId,
        traceId,
        projectName: project.name,
        name: spanName,
        source: 'sdk',
        type: 'llm',
        provider: values.provider,
        model: `${values.provider}-model`,
        input: { prompt: `prompt ${which}` },
        output: { completion: `completion ${which}` },
        metadata: {
          component: values.component,
          // The same value as the dedicated column above: the span quick filter
          // must route THIS key to the provider column rather than to metadata.
          provider: values.provider,
          stage: 'retrieve',
        },
      });

      return { traceId, traceName, spanId, spanName };
    };

    try {
      const alpha = await seedPair('alpha');
      const beta = await seedPair('beta');

      // Ingestion is eventually consistent: both pairs have to be readable before
      // anything below can mean anything.
      await expect
        .poll(
          async () =>
            (await backendClient.listTraceIds({ projectId: project.id })).length,
          { message: 'seeded traces visible to the API', timeout: 30_000 },
        )
        .toBe(2);
      await expect
        .poll(
          async () => (await backendClient.listSpanIds({ projectId: project.id })).length,
          { message: 'seeded spans visible to the API', timeout: 30_000 },
        )
        .toBe(2);

      // Each filter shape the specs drive, asserted server-side first. These are
      // the same field/type/operator/key tuples `resolveQuickFilterTarget`
      // produces, so a UI assertion that the table narrowed is comparing against
      // an answer the backend already agreed to.
      const spanMetadataFilter: BackendFilter[] = [
        {
          field: 'metadata',
          type: 'dictionary',
          key: 'component',
          operator: 'contains',
          value: QUICK_FILTER_SEED.alpha.component,
        },
      ];
      const spanProviderFilter: BackendFilter[] = [
        {
          field: 'provider',
          type: 'string',
          operator: 'contains',
          value: QUICK_FILTER_SEED.alpha.provider,
        },
      ];
      const traceMetadataFilter: BackendFilter[] = [
        {
          field: 'metadata',
          type: 'dictionary',
          key: 'tenant',
          operator: 'contains',
          value: QUICK_FILTER_SEED.alpha.tenant,
        },
      ];

      expect(
        await backendClient.listSpanIds({ projectId: project.id, filters: spanMetadataFilter }),
        'spans matching metadata.component contains the alpha value',
      ).toEqual([alpha.spanId]);
      expect(
        await backendClient.listSpanIds({ projectId: project.id, filters: spanProviderFilter }),
        'spans matching provider contains the alpha value',
      ).toEqual([alpha.spanId]);
      expect(
        await backendClient.listTraceIds({ projectId: project.id, filters: traceMetadataFilter }),
        'traces matching metadata.tenant contains the alpha value',
      ).toEqual([alpha.traceId]);

      const ref: QuickFilterLogsRef = {
        projectId: project.id,
        projectName: project.name,
        alpha,
        beta,
      };
      await testInfo.attach('opik.quickFilterLogs', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo) && traceIds.length) {
        try {
          await backendClient.deleteTraces(traceIds);
        } catch (err) {
          console.warn('[quickFilterLogs fixture] delete warning for the seeded traces:', err);
        }
      }
    }
  },
});

export { expect };
