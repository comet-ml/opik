import { expect } from '@playwright/test';
import { test as baseTest } from './queue-form-seed.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type SpanBatchSeed, type TraceBatchSeed } from '../core/backend';

/**
 * The token the search is driven on throughout.
 *
 * Deliberately NOT `zephyr`/`quetzal` — `threadSearchPopulation` owns those, and
 * two fixtures sharing a marker would make a cross-project leak read as a
 * correct answer if the two ever ran against one project.
 */
export const VERSION_MARKER = 'marmoset';

/** How long the `trace_threads` aggregation may take to catch up with the traces. */
const THREADS_VISIBLE_TIMEOUT_MS = 180_000;

/** Usage on each trace's one llm span, so every thread aggregates something real. */
const SPAN_USAGE = { prompt_tokens: 120, completion_tokens: 45, total_tokens: 165 } as const;

/** What a seeded thread is for, and the text each of its three versions carries. */
export interface ThreadVersionRowRef {
  /** The producer-chosen thread id, which is also the table row's `data-row-id`. */
  threadId: string;
  /** The thread's single trace — the row a spec PATCHes to write a new version. */
  traceId: string;
  spanId: string;
  /**
   * What this row is in the scenario:
   *
   *  - `gains` — seeded WITHOUT the marker, PATCHed INTO carrying it. Must be
   *    absent from a searched read before the update and present after.
   *  - `loses` — seeded WITH the marker, PATCHed OUT of carrying it. Must be
   *    present before and absent after, which is the half a search reading a
   *    stale version gets wrong.
   *  - `decoy` — never carries the marker and is never PATCHed. On the seed so
   *    that "returned everything" cannot pass as either answer.
   */
  role: 'gains' | 'loses' | 'decoy';
  /** A token unique to this thread, present in whichever version is current. */
  token: string;
}

export interface ThreadVersionSearchRef {
  rows: ThreadVersionRowRef[];
  /** The term every search in the spec is driven on. */
  marker: string;
  /** The one thread the marker matches BEFORE any update — the `loses` row. */
  threadIdsMatchingBeforeUpdate: string[];
  /** The one thread the marker matches AFTER both updates — the `gains` row. */
  threadIdsMatchingAfterUpdate: string[];
  /** Every seeded thread id, sorted — the answer an unsearched read must give. */
  allThreadIds: string[];
  /** Project-scoped lookup, so the spec addresses rows by role rather than index. */
  byRole: (role: ThreadVersionRowRef['role']) => ThreadVersionRowRef;
  /**
   * The payload a spec PATCHes a row to, by role.
   *
   * On the ref rather than built in the spec because the fixture has already
   * asserted what these texts do and do not contain; a spec composing its own
   * replacement payload would be re-deciding the premise the fixture proved.
   */
  updatedPayload: (
    role: 'gains' | 'loses',
  ) => { input: Record<string, unknown>; output: Record<string, unknown> };
  /** The token the `loses` row's NEW version carries, and its old one did not. */
  losesNewToken: string;
  /**
   * A phrase the `gains` row's SEEDED version carried and its updated one does
   * not, and which reaches no other row.
   *
   * The only assertion in the spec that can fail solely on a clause reading
   * EVERY stored version rather than the latest one: a search for it must come
   * back empty once the update has landed.
   */
  gainsOldPhrase: string;
}

export interface ThreadVersionSearchFixtures {
  threadVersionSearch: ThreadVersionSearchRef;
}

/** Whether `text` contains the marker, folded the way `ilike` compares. */
const carries = (value: unknown, marker: string): boolean =>
  JSON.stringify(value).toLowerCase().includes(marker.toLowerCase());

export const test = baseTest.extend<ThreadVersionSearchFixtures>({
  /**
   * Three single-trace threads, shaped so that one trace UPDATE flips the answer
   * a free-text search gives.
   *
   * This is the semantic opik#9264 introduced and the one thing the estate's
   * existing `thread-free-text-search.spec.ts` cannot speak to: that spec seeds
   * every trace once and never updates one, so a search clause matching the
   * FIRST stored version of a trace and one matching the LATEST give identical
   * answers over its whole population. 9264 moved the `ilike` outside `FINAL`
   * and behind a key-only `(id, max(last_updated_at))` pass, so which version is
   * read is now a decision the query makes explicitly — and a wrong one is
   * silent: an edited conversation simply stops being in the list, with no error
   * anywhere and the rest of the table perfectly healthy.
   *
   * Hence two rows that move in OPPOSITE directions:
   *
   *  - `gains` is seeded without the marker and PATCHed into carrying it. A
   *    clause stuck on the first version leaves it out.
   *  - `loses` is seeded with the marker and PATCHed out of carrying it. A
   *    clause stuck on the first version still returns it — which the `gains`
   *    half alone would not catch, because a clause reading EVERY version
   *    satisfies that half too.
   *
   * Only together do the two pin "the latest version, and only the latest".
   *
   * The `decoy` never matches and is never touched, so neither "returned
   * everything" nor "returned nothing" can pass as either answer, and so the
   * update is shown to have moved the two rows it names rather than the project.
   *
   * The fixture seeds and proves; it does NOT perform the updates. The PATCH is
   * the behaviour under test, and a fixture that had already applied it would
   * leave the spec unable to assert the BEFORE answer — and so unable to show
   * the search ever changed its mind rather than having always answered this.
   *
   * Teardown deletes the traces, which takes their spans and collapses the
   * `trace_threads` rows with them.
   */
  threadVersionSearch: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const traceIds: string[] = [];

    try {
      const marker = VERSION_MARKER.toLowerCase();
      const roles: Array<ThreadVersionRowRef['role']> = ['gains', 'loses', 'decoy'];

      const rows: ThreadVersionRowRef[] = [];
      const traces: TraceBatchSeed[] = [];
      const spans: SpanBatchSeed[] = [];
      const base = new Date();

      // The text each row is seeded with, and (for the two that move) the text
      // it is PATCHed to. Built here so the fixture's own checks below run over
      // the same objects the spec will later send.
      // A phrase the `gains` row starts with and nothing else carries. The
      // decoy's prose deliberately shares the TAIL of it ("about the other
      // primate") and not the whole, so a clause that had widened to a looser
      // match answers two rows here rather than one.
      const gainsOldPhrase = 'quiet question about the other primate';

      const seededText: Record<ThreadVersionRowRef['role'], string> = {
        // `gains` must start with NO marker anywhere the clause looks.
        gains: `a ${gainsOldPhrase}`,
        loses: `a question about the ${marker} and its habits`,
        decoy: 'an unrelated question about the other primate',
      };
      const updatedText: Record<'gains' | 'loses', string> = {
        gains: `a revised question about the ${marker} and its diet`,
        // The replacement text carries a token of its own rather than merely
        // dropping the marker: "the old term no longer matches" and "the trace
        // is gone from the index" are different outcomes, and only a search for
        // the NEW text tells them apart.
        loses: `a revised question about the ${testNamespace}-tamarin instead`,
      };

      roles.forEach((role, position) => {
        const threadId = `${testNamespace}-thread-${role}`;
        const traceId = uuid7();
        const spanId = uuid7();
        const token = `${testNamespace}-tok-${role}`;
        const text = `${seededText[role]} [${token}]`;
        const start = new Date(base.getTime() + position * 20);

        traces.push({
          id: traceId,
          // The trace name carries no searchable text of the scenario's: the
          // thread clause reads `thread_id`, `id`, `input` and `output` and not
          // the name, so a name holding the marker could only ever make a
          // failing build look like a passing one.
          name: `${testNamespace}-trace-${role}`,
          input: { prompt: text },
          output: { answer: `an answer about ${text}` },
          threadId,
          startTime: start,
          // A non-zero span so the thread's `duration` aggregate is a real
          // number rather than null.
          endTime: new Date(start.getTime() + 1_500),
        });
        spans.push({
          id: spanId,
          traceId,
          name: `${testNamespace}-span-${role}`,
          type: 'llm',
          model: 'gpt-4o-mini',
          provider: 'openai',
          usage: { ...SPAN_USAGE },
          input: { prompt: text },
          output: { answer: `an answer about ${text}` },
          startTime: start,
          endTime: new Date(start.getTime() + 1_400),
        });
        rows.push({ threadId, traceId, spanId, role, token });
      });

      const updatedPayload = (role: 'gains' | 'loses') => {
        const row = rows.find((r) => r.role === role)!;
        const text = `${updatedText[role]} [${row.token}]`;
        return { input: { prompt: text }, output: { answer: `an answer about ${text}` } };
      };

      // ---- the fixture proves its own premises, before anything is written ----

      // The load-bearing one, same as `threadSearchPopulation`'s: the clause
      // matches `thread_id` and `id` as well as the payload, and the ids are
      // built from the run prefix and the test's own title — neither of which
      // this fixture controls. A marker occurring in one would make every row
      // match for a reason that has nothing to do with which version was read.
      for (const row of rows) {
        expect(
          row.threadId.toLowerCase().includes(marker),
          `thread id '${row.threadId}' must not contain '${marker}' — the search clause ` +
            'matches the thread id, so it would match for the wrong reason',
        ).toBe(false);
        expect(
          row.token.toLowerCase().includes(marker),
          `the per-thread token '${row.token}' must not contain '${marker}', or a search ` +
            'for the token and a search for the marker are the same question',
        ).toBe(false);
      }

      const seededCarries = (role: ThreadVersionRowRef['role']) => {
        const position = roles.indexOf(role);
        return (
          carries(traces[position].input, marker) || carries(traces[position].output, marker)
        );
      };

      expect(
        seededCarries('loses'),
        'the `loses` row must be seeded CARRYING the marker, or it cannot lose it',
      ).toBe(true);
      expect(
        seededCarries('gains'),
        'the `gains` row must be seeded WITHOUT the marker, or its update proves nothing',
      ).toBe(false);
      expect(
        seededCarries('decoy'),
        'the `decoy` row must never carry the marker',
      ).toBe(false);

      // And the mirror, over the payloads the spec is going to send.
      const gainsUpdate = updatedPayload('gains');
      const losesUpdate = updatedPayload('loses');
      expect(
        carries(gainsUpdate.input, marker) && carries(gainsUpdate.output, marker),
        'the `gains` update must put the marker on BOTH payload columns — an update ' +
          'mentioning one column leaves the other absent from the version the clause reads',
      ).toBe(true);
      expect(
        carries(losesUpdate.input, marker) || carries(losesUpdate.output, marker),
        'the `loses` update must drop the marker from both payload columns',
      ).toBe(false);
      const losesNewToken = `${testNamespace}-tamarin`;
      expect(
        carries(losesUpdate.input, losesNewToken),
        "the `loses` update must carry a term of its own, or \"the old term stopped " +
          'matching" cannot be told from "the trace left the index"',
      ).toBe(true);
      // And that term must reach no other row, in either its seeded or its
      // updated text, or "exactly the `loses` thread" is the wrong expected
      // answer for a search on it.
      for (const other of rows.filter((r) => r.role !== 'loses')) {
        const position = roles.indexOf(other.role);
        expect(
          carries(
            [
              traces[position].input,
              traces[position].output,
              other.threadId,
              other.role === 'decoy' ? '' : updatedText[other.role],
            ],
            losesNewToken,
          ),
          `'${losesNewToken}' must not occur anywhere thread '${other.threadId}' is searched`,
        ).toBe(false);
      }

      // The `gains` row's old phrase must reach exactly that row while it is
      // current, and no row at all once it is superseded.
      const phraseCarriers = rows.filter((other) => {
        const position = roles.indexOf(other.role);
        return carries([traces[position].input, traces[position].output], gainsOldPhrase);
      });
      expect(
        phraseCarriers.map((c) => c.threadId),
        `'${gainsOldPhrase}' must occur in exactly the \`gains\` row's seeded payload`,
      ).toEqual([rows.find((r) => r.role === 'gains')!.threadId]);
      expect(
        carries([gainsUpdate.input, gainsUpdate.output], gainsOldPhrase),
        `the \`gains\` update must drop '${gainsOldPhrase}', or a search for it after the ` +
          'update cannot distinguish a stale version from a current one',
      ).toBe(false);

      // Each per-thread token resolves to one thread, or "exactly this thread"
      // is the wrong expected answer for a search on it.
      for (const row of rows) {
        const carriers = rows.filter((other) =>
          [
            seededText[other.role],
            other.role === 'decoy' ? '' : updatedText[other.role],
            other.threadId,
            other.token,
          ]
            .join(' ')
            .toLowerCase()
            .includes(row.token.toLowerCase()),
        );
        expect(
          carriers.map((c) => c.threadId),
          `token '${row.token}' must occur in exactly one thread's searchable text`,
        ).toEqual([row.threadId]);
      }

      const ids = [...traces.map((t) => t.id), ...spans.map((s) => s.id)];
      expect(new Set(ids).size, 'the seed minted no duplicate id').toBe(ids.length);

      // ---- write ----

      await backendClient.createTracesBatch({ projectName: project.name, traces });
      traceIds.push(...traces.map((t) => t.id));
      await backendClient.createSpansBatch({ projectName: project.name, spans });

      // Threads are materialised from the traces that share a `thread_id`, on a
      // separate eventually-consistent path from the traces themselves — so the
      // wait is on the THREAD listing. Polled on the unsearched total: a poll on
      // a searched read would keep retrying the very shortfall this seed exists
      // to detect and then fail as a timeout rather than as a missing thread.
      await expect
        .poll(
          async () => (await backendClient.listThreads({ projectId: project.id, size: 1 })).total,
          {
            message: 'every seeded thread must be aggregated before the spec reads anything',
            timeout: THREADS_VISIBLE_TIMEOUT_MS,
            intervals: [2_000, 2_000, 5_000],
          },
        )
        .toBe(rows.length);

      const sorted = (values: string[]) => [...values].sort();
      const row = (role: ThreadVersionRowRef['role']) => rows.find((r) => r.role === role)!;

      const ref: ThreadVersionSearchRef = {
        rows,
        marker,
        threadIdsMatchingBeforeUpdate: [row('loses').threadId],
        threadIdsMatchingAfterUpdate: [row('gains').threadId],
        allThreadIds: sorted(rows.map((r) => r.threadId)),
        byRole: row,
        updatedPayload,
        losesNewToken,
        gainsOldPhrase,
      };

      await testInfo.attach('opik.threadVersionSearch', {
        body: JSON.stringify(
          {
            projectId: project.id,
            projectName: project.name,
            marker: ref.marker,
            rows: ref.rows,
            matchingBefore: ref.threadIdsMatchingBeforeUpdate,
            matchingAfter: ref.threadIdsMatchingAfterUpdate,
          },
          null,
          2,
        ),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo) && traceIds.length > 0) {
        try {
          await backendClient.deleteTraces(traceIds);
        } catch (err) {
          // Never rethrow from teardown: a cleanup failure must not replace the
          // test's own error.
          console.warn('[threadVersionSearch fixture] trace delete warning:', err);
        }
      }
    }
  },
});

export { expect } from './queue-form-seed.fixture';
