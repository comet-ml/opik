import { expect } from '@playwright/test';
import { test as baseTest } from './suite-item-nav.fixture';
import { isUuidWindowRejection } from './uuid-window-guard';
import { shouldLeaveArtifacts } from '../core/artifacts';
import {
  uuid7,
  uuid7Moment,
  type BackendClient,
  type SpanBatchSeed,
  type TraceBatchSeed,
} from '../core/backend';

/**
 * The token the matching rows carry and nothing else in the project does.
 *
 * Every character of it is incidental except `q`: that one makes
 * `SEARCH_NEEDLE_CHAR` below a legitimate one-character search. `q` appears in
 * no hex digit (so no id, trace id or span id can contain it), in no span
 * `type` the seed writes, and in nothing the run prefix contributes — and the
 * fixture asserts the last of those rather than trusting it, because the run
 * prefix includes the test's own title.
 */
export const SEARCH_NEEDLE = 'quokka';

/**
 * A one-character search that must return the same set as the whole needle.
 *
 * The narrowest possible term, and the one the release report asks to be driven
 * by hand: a single character maximises the number of rows the scan has to
 * reject, so a pruning bound that is too tight loses rows here first.
 */
export const SEARCH_NEEDLE_CHAR = 'q';

/**
 * A second, independent axis cutting across the match/decoy split, so a
 * structured filter can be composed with the search.
 *
 * Group A holds some matching AND some decoy rows, which is what makes the
 * composition meaningful: a `name contains <group A>` filter on its own returns
 * more rows than the intersection does, so neither the filter nor the search
 * can be the only thing that acted.
 */
const GROUP_A = 'alfa';
const GROUP_B = 'bravo';

/** 14 matching rows and 11 decoys — 25 in the project. */
const MATCH_COUNT = 14;
const DECOY_COUNT = 11;
/** How many of the 14 matching rows sit in group A; the rest are in group B. */
const MATCH_IN_GROUP_A = 6;
/** How many of the 11 decoys sit in group A; the rest are in group B. */
const DECOY_IN_GROUP_A = 5;

/**
 * How many distinct calendar weeks the seed TRIES to spread its ids over.
 *
 * A parameter rather than a hard-coded one week, and that is the point of it:
 * what opik#8767 actually changed is a pre-pass that bounds a searched scan to
 * the weeks a project has rows in, so a single-week population cannot observe
 * the bound at all — it is right for any bound that includes "this week".
 *
 * Whether the spread is achieved is the environment's call, not this fixture's.
 * `UUIDv7TimestampValidator` in reject mode refuses an id outside
 * `[now - window, now + window]`, and a deployment with a window shorter than
 * a week (staging runs `PT24H`) cannot hold a multi-week project at all. So the
 * fixture probes, falls back to a single week when the probe is refused, and
 * REPORTS what it achieved as `weeksCovered` — which the spec asserts against
 * the ids themselves, so a fallback is visible rather than silent.
 *
 * Three rather than two: with two, a bound that happened to span exactly the
 * current week plus one would still pass.
 */
const REQUESTED_WEEK_SPREAD = 3;

/** One calendar week, as the offset between two seeded week buckets. */
const WEEK_MS = 7 * 24 * 60 * 60 * 1000;

/** How long a just-written batch may take to become queryable. */
const QUERYABLE_TIMEOUT_MS = 120_000;

/** One seeded row: its trace, the single span mirroring it, and what it is for. */
export interface SearchRowRef {
  traceId: string;
  spanId: string;
  /** The trace's name; the span's is this plus a `-span` suffix. */
  name: string;
  /** Whether this row carries `SEARCH_NEEDLE` and so must be returned by a search for it. */
  matches: boolean;
  /** `GROUP_A` or `GROUP_B` — the axis the structured filter cuts on. */
  group: string;
  /** The instant this row's ids embed, which is what the week pre-pass buckets on. */
  idMoment: Date;
}

export interface SearchPopulationRef {
  rows: SearchRowRef[];
  /** The needle, re-exported on the ref so a spec never spells it a second time. */
  needle: string;
  needleChar: string;
  /** The group the composed filter selects, and the filter value to send. */
  filterGroup: string;
  /** Trace ids carrying the needle, sorted. The answer a search must give. */
  matchingTraceIds: string[];
  /** Span ids carrying the needle, sorted. */
  matchingSpanIds: string[];
  /** Every seeded trace id, sorted — the answer an unsearched read must give. */
  allTraceIds: string[];
  allSpanIds: string[];
  /** Matching trace ids that are also in `filterGroup`, sorted: the intersection. */
  intersectionTraceIds: string[];
  intersectionSpanIds: string[];
  /** Every trace id in `filterGroup`, matching or not, sorted: the filter alone. */
  filterGroupTraceIds: string[];
  filterGroupSpanIds: string[];
  /**
   * Distinct calendar weeks the seeded ids actually fell into.
   *
   * 1 on an environment that refused backdated ids, `REQUESTED_WEEK_SPREAD`
   * otherwise. Reported rather than assumed so the spec can state which of the
   * two it exercised instead of quietly claiming the multi-week bound.
   */
  weeksCovered: number;
  /** `REQUESTED_WEEK_SPREAD`, so a spec can say what was asked for as well as got. */
  weeksRequested: number;
}

export interface SearchPopulationFixtures {
  searchPopulation: SearchPopulationRef;
}

/** The Monday-00:00-UTC bucket an instant falls in — how the backend groups weeks. */
const weekBucket = (moment: Date): number => {
  const utc = Date.UTC(
    moment.getUTCFullYear(),
    moment.getUTCMonth(),
    moment.getUTCDate(),
  );
  // getUTCDay() is 0 for Sunday; shift so Monday is 0.
  const mondayOffset = (new Date(utc).getUTCDay() + 6) % 7;
  return utc - mondayOffset * 24 * 60 * 60 * 1000;
};

/**
 * Whether this deployment accepts an id backdated by one week.
 *
 * Probed with a real write because the mode is not readable from the client —
 * `uuidValidation.enabled` / `auditOnly` are backend config. The probe trace is
 * deleted again and the deletion is waited out: this fixture's own spec asserts
 * exact per-project counts, so a lingering probe would hold every one of them
 * one too high.
 *
 * Returns a boolean rather than skipping the test, unlike
 * `skipUnlessBackdatedIdsAccepted`: a single-week population still exercises
 * search exactness completely, and that is most of what this seed is for. Only
 * the week spread degrades.
 */
async function backdatedIdsAccepted(
  backendClient: BackendClient,
  projectName: string,
): Promise<boolean> {
  const id = uuid7(new Date(Date.now() - WEEK_MS));
  try {
    await backendClient.createTraceWithSource({
      id,
      projectName,
      name: `week-spread-probe-${id.slice(0, 8)}`,
      source: 'sdk',
    });
  } catch (err) {
    if (isUuidWindowRejection(err)) return false;
    throw err;
  }

  await backendClient.deleteTraces([id]);
  await expect
    .poll(async () => await backendClient.getTrace(id), {
      message: 'the week-spread probe trace must be gone before the real seed is counted',
      timeout: 30_000,
    })
    .toBeNull();
  return true;
}

export const test = baseTest.extend<SearchPopulationFixtures>({
  /**
   * 25 traces, each with exactly one span: 14 carrying a needle token in their
   * name, input and output, and 11 decoys carrying none.
   *
   * Shaped for ONE question — does a free-text search return exactly the rows
   * that match, in the listing, across a page boundary and in the stats. Three
   * choices make that question answerable:
   *
   *  - **The decoys outnumber nothing.** 11 against 14 means neither "returned
   *    everything" nor "returned nothing" can be mistaken for the right answer,
   *    and the two numbers are far enough apart to read in a failure message.
   *  - **`SEARCH_NEEDLE_CHAR` is exact too.** The fixture asserts that the
   *    character appears nowhere in any decoy row and in no seeded id, so a
   *    one-character search has the same correct answer as the whole needle.
   *    That assertion is the fixture proving it can discriminate: without it a
   *    namespace that happened to contain a `q` would make the one-character
   *    test pass while verifying nothing.
   *  - **The group axis crosses the match axis.** `filterGroup` holds matching
   *    AND decoy rows, so the composed filter+search intersection is strictly
   *    smaller than either side alone.
   *
   * Seeded over REST in two batch writes rather than through the SDK bridge:
   * 50 sequential writes is the quickest route to the workspace ingestion rate
   * limit, and a rate-limited seed lands partially — which, from inside the
   * spec, is indistinguishable from a search that lost rows.
   *
   * Teardown deletes the traces, which takes their spans with them. Neither the
   * `project` fixture's delete nor `global-teardown`'s run-prefix sweep would:
   * the sweep knows about projects, datasets and experiments, and deleting a
   * project does not delete its traces.
   */
  searchPopulation: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const traceIds: string[] = [];

    try {
      const spreadAchieved = (await backdatedIdsAccepted(backendClient, project.name))
        ? REQUESTED_WEEK_SPREAD
        : 1;

      const rows: SearchRowRef[] = [];
      const traces: TraceBatchSeed[] = [];
      const spans: SpanBatchSeed[] = [];

      // Built as one flat list so the week offset cycles across BOTH the
      // matching and the decoy rows: a spread that only aged the decoys would
      // leave every match in the current week, and the bound under test would
      // never have to reach past it.
      const seeds: Array<{ matches: boolean; index: number; group: string }> = [];
      for (let i = 0; i < MATCH_COUNT; i++) {
        seeds.push({ matches: true, index: i, group: i < MATCH_IN_GROUP_A ? GROUP_A : GROUP_B });
      }
      for (let i = 0; i < DECOY_COUNT; i++) {
        seeds.push({ matches: false, index: i, group: i < DECOY_IN_GROUP_A ? GROUP_A : GROUP_B });
      }

      const now = Date.now();
      seeds.forEach((seed, position) => {
        const idMoment = new Date(now - (position % spreadAchieved) * WEEK_MS);
        const traceId = uuid7(idMoment);
        const spanId = uuid7(idMoment);
        const label = seed.matches ? 'match' : 'decoy';
        const ordinal = String(seed.index).padStart(2, '0');
        // The needle is a suffix on the matching names and absent from the
        // decoy ones. Everything before it is shared, so the two sets differ by
        // the needle and by nothing else a search could key on.
        const name = seed.matches
          ? `${testNamespace}-${label}-${ordinal}-${seed.group}-${SEARCH_NEEDLE}`
          : `${testNamespace}-${label}-${ordinal}-${seed.group}`;

        // `input` and `output` carry the needle as well as the name does, so a
        // search that only looked at one column still has to find the same 14 —
        // and a decoy's text is deliberately plain prose with no needle in it.
        const input = seed.matches
          ? { prompt: `tell me about the ${SEARCH_NEEDLE} please` }
          : { prompt: `tell me about the other animal please` };
        const output = seed.matches
          ? { answer: `the ${SEARCH_NEEDLE} is a small marsupial` }
          : { answer: `the other animal is a small marsupial` };

        traces.push({
          id: traceId,
          name,
          input,
          output,
          startTime: idMoment,
          endTime: new Date(idMoment.getTime() + 1_000),
        });
        spans.push({
          id: spanId,
          traceId,
          name: `${name}-span`,
          type: 'general',
          input,
          output,
          startTime: idMoment,
          endTime: new Date(idMoment.getTime() + 1_000),
        });
        rows.push({ traceId, spanId, name, matches: seed.matches, group: seed.group, idMoment });
      });

      // ---- the fixture proves its own premises, before anything is written ----

      const needle = SEARCH_NEEDLE.toLowerCase();
      const char = SEARCH_NEEDLE_CHAR.toLowerCase();

      // Everything a search can match on, per row, folded the way `ilike` folds
      // it. Deliberately assembled from the SAME objects that go on the wire,
      // so it cannot drift from what was actually seeded.
      const searchableText = (position: number): string =>
        JSON.stringify([traces[position], spans[position]]).toLowerCase();

      rows.forEach((row, position) => {
        const text = searchableText(position);
        if (row.matches) {
          expect(
            text.includes(needle),
            `matching row '${row.name}' must carry the needle somewhere a search looks`,
          ).toBe(true);
        } else {
          // The load-bearing one. If a decoy carries the character at all —
          // from the run prefix, the test title, a minted id — then the
          // one-character search has a different correct answer than the needle
          // does, and the spec's one-character assertion would be testing
          // nothing while passing.
          expect(
            text.includes(char),
            `decoy row '${row.name}' must contain no '${SEARCH_NEEDLE_CHAR}' anywhere a ` +
              'search looks, or a one-character search is not exact on this seed',
          ).toBe(false);
        }
      });

      const allIds = [...traces.map((t) => t.id), ...spans.map((s) => s.id)];
      expect(
        allIds.filter((id) => id.toLowerCase().includes(char)),
        `no seeded id may contain '${SEARCH_NEEDLE_CHAR}' — the span search clause matches ` +
          'on id and trace_id too',
      ).toEqual([]);
      expect(new Set(allIds).size, 'the seed minted no duplicate id').toBe(allIds.length);

      const observedWeeks = new Set(rows.map((row) => weekBucket(uuid7Moment(row.traceId))));
      expect(
        observedWeeks.size,
        'the ids must fall into exactly as many week buckets as the seed set out to use',
      ).toBe(spreadAchieved);

      // ---- write ----

      await backendClient.createTracesBatch({ projectName: project.name, traces });
      traceIds.push(...traces.map((t) => t.id));
      await backendClient.createSpansBatch({ projectName: project.name, spans });

      // Both populations, not just the traces: the spans land on a separate
      // eventually-consistent path, and a spans assertion made against a
      // half-ingested seed is a test that cannot fail.
      await expect
        .poll(
          async () =>
            (await backendClient.searchTraceIdsPage({ projectId: project.id, page: 1, size: 1 }))
              .total,
          {
            message: 'every seeded trace must be queryable before the spec reads anything',
            timeout: QUERYABLE_TIMEOUT_MS,
            intervals: [2_000, 2_000, 5_000],
          },
        )
        .toBe(traces.length);
      await expect
        .poll(
          async () =>
            (await backendClient.searchSpanIdsPage({ projectId: project.id, page: 1, size: 1 }))
              .total,
          {
            message: 'every seeded span must be queryable before the spec reads anything',
            timeout: QUERYABLE_TIMEOUT_MS,
            intervals: [2_000, 2_000, 5_000],
          },
        )
        .toBe(spans.length);

      const sorted = (values: string[]) => [...values].sort();
      const matching = rows.filter((row) => row.matches);
      const inGroup = rows.filter((row) => row.group === GROUP_A);
      const intersection = rows.filter((row) => row.matches && row.group === GROUP_A);

      const ref: SearchPopulationRef = {
        rows,
        needle: SEARCH_NEEDLE,
        needleChar: SEARCH_NEEDLE_CHAR,
        filterGroup: GROUP_A,
        matchingTraceIds: sorted(matching.map((r) => r.traceId)),
        matchingSpanIds: sorted(matching.map((r) => r.spanId)),
        allTraceIds: sorted(rows.map((r) => r.traceId)),
        allSpanIds: sorted(rows.map((r) => r.spanId)),
        intersectionTraceIds: sorted(intersection.map((r) => r.traceId)),
        intersectionSpanIds: sorted(intersection.map((r) => r.spanId)),
        filterGroupTraceIds: sorted(inGroup.map((r) => r.traceId)),
        filterGroupSpanIds: sorted(inGroup.map((r) => r.spanId)),
        weeksCovered: observedWeeks.size,
        weeksRequested: REQUESTED_WEEK_SPREAD,
      };

      await testInfo.attach('opik.searchPopulation', {
        body: JSON.stringify(
          {
            projectId: project.id,
            projectName: project.name,
            needle: ref.needle,
            needleChar: ref.needleChar,
            filterGroup: ref.filterGroup,
            weeksRequested: ref.weeksRequested,
            weeksCovered: ref.weeksCovered,
            counts: {
              all: ref.allTraceIds.length,
              matching: ref.matchingTraceIds.length,
              inFilterGroup: ref.filterGroupTraceIds.length,
              intersection: ref.intersectionTraceIds.length,
            },
            rows: ref.rows.map((row) => ({
              name: row.name,
              matches: row.matches,
              group: row.group,
              idMoment: row.idMoment.toISOString(),
            })),
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
          console.warn('[searchPopulation fixture] trace delete warning:', err);
        }
      }
    }
  },
});

export { expect } from './suite-item-nav.fixture';
