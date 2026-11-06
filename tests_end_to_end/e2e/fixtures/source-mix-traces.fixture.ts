import { expect } from '@playwright/test';
import { test as baseTest } from './openrouter-repriced-spans.fixture';
import { uuid7 } from '../core/backend';

/** One seeded trace and the `source` it was written with. */
export interface SourceMixTraceRef {
  id: string;
  name: string;
  /**
   * The `source` the row carries, or `null` for the legacy row written with no
   * `source` key at all. Never the string `'unknown'`: that is the ClickHouse
   * default for pre-source-tracking rows and `api.Source` deliberately refuses
   * it on ingest, so `null` is the only way to produce one.
   */
  source: 'sdk' | 'experiment' | 'playground' | 'optimization' | 'evaluator' | null;
}

export interface SourceMixTracesRef {
  /** Every seeded row, in write order. */
  all: SourceMixTraceRef[];
  /**
   * The rows a `source = sdk` read must return: the two `sdk` rows AND the
   * legacy no-source one, because `Source.legacyFallbackDbValue` folds the
   * stored `unknown` value into an `= sdk` filter and nothing else.
   */
  countedIds: string[];
  /**
   * The rows it must exclude — one per remaining `Source` value. Every one of
   * them is a run the product logged on the user's behalf rather than the
   * user's own traffic, which is the distinction opik#8808 added.
   */
  excludedIds: string[];
  /** What the home page's "Traces logged today" must read: `countedIds.length`. */
  expectedTodayCount: number;
}

export interface SourceMixTracesFixtures {
  sourceMixTraces: SourceMixTracesRef;
}

/**
 * How many rows carry each `source`, and in which order they are written.
 *
 * `sdk` twice so the expected count (3) can never coincide with "one row per
 * source" or with the number of excluded rows — a filter that returned the
 * wrong set but the right size would still fail.
 *
 * Every other `Source` value appears exactly once, deliberately exhaustively:
 * the subject is a filter that must admit `sdk` and nothing else, so a value
 * left unseeded is a value nobody checked is excluded.
 */
const SOURCE_SEEDS: ReadonlyArray<{ source: SourceMixTraceRef['source']; count: number }> = [
  { source: 'sdk', count: 2 },
  { source: 'playground', count: 1 },
  { source: 'experiment', count: 1 },
  { source: 'optimization', count: 1 },
  { source: 'evaluator', count: 1 },
  // The legacy shape: no `source` key on the write at all.
  { source: null, count: 1 },
];

/**
 * How close to UTC midnight is too close to start.
 *
 * `RecentActivityService` buckets the daily counts by UTC day and labels the
 * bucket matching `LocalDate.now(ZoneOffset.UTC)` as today, so a run that
 * seeded at 23:59:58 and read the page at 00:00:04 is comparing a seed in one
 * bucket against a label naming the next. Five minutes comfortably covers the
 * seed, the ingestion poll and the two page loads.
 *
 * Stated as a precondition skip rather than absorbed into the assertions: the
 * alternative is a spec that tolerates the count being split across two
 * buckets, which is a spec that no longer pins the count at all.
 */
const UTC_MIDNIGHT_GUARD_MS = 5 * 60 * 1000;

function msUntilUtcMidnight(now: Date): number {
  const nextMidnight = Date.UTC(
    now.getUTCFullYear(),
    now.getUTCMonth(),
    now.getUTCDate() + 1,
    0,
    0,
    0,
    0,
  );
  return nextMidnight - now.getTime();
}

/**
 * One trace per `Source` value in a fresh project, all logged today, plus the
 * legacy no-source row.
 *
 * The seed behind the home page's "Traces logged today" counter (opik#8808).
 * It exists as a fixture rather than inline so the spec can open a browser
 * knowing the whole population landed: the counter is an aggregate, so a seed
 * that half arrived produces a smaller number rather than an error, and a UI
 * assertion over it would be comparing one wrong number against another.
 *
 * No teardown of its own, and that is checked rather than assumed: traces are
 * project-scoped and cascade with the project, which the `project` fixture
 * deletes. (The reverse is NOT true — deleting a project does not delete
 * entities that merely reference it — but traces do go with it.)
 */
export const test = baseTest.extend<SourceMixTracesFixtures>({
  sourceMixTraces: async ({ project, backendClient }, use, testInfo) => {
    testInfo.skip(
      msUntilUtcMidnight(new Date()) < UTC_MIDNIGHT_GUARD_MS,
      'within 5 minutes of UTC midnight: the daily trace bucket would roll over mid-test',
    );

    const all: SourceMixTraceRef[] = [];
    for (const seed of SOURCE_SEEDS) {
      for (let index = 0; index < seed.count; index += 1) {
        const label = seed.source ?? 'legacy-no-source';
        const name = `source-mix-${label}-${index}`;
        const id = uuid7();
        await backendClient.createTraceWithSource({
          id,
          projectName: project.name,
          name,
          source: seed.source,
          // `end_time` set so the row is a complete write rather than a partial
          // one. Start and end are both left to default to now: the bucket is
          // the current UTC day, which the midnight guard above protects.
          endTime: new Date(),
        });
        all.push({ id, name, source: seed.source });
      }
    }

    const countedIds = all.filter((t) => t.source === 'sdk' || t.source === null).map((t) => t.id);
    const excludedIds = all
      .filter((t) => t.source !== 'sdk' && t.source !== null)
      .map((t) => t.id);

    // The seed's own precondition, asserted before any browser opens: the whole
    // population is readable. Without this the spec could not tell "the filter
    // excluded four rows" from "four rows never landed", and the second reads
    // as coverage forever.
    await expect
      .poll(
        async () =>
          (await backendClient.searchTraceIdsPage({ projectId: project.id, size: 200 })).total,
        { timeout: 90_000, intervals: [1_000, 2_000, 5_000] },
      )
      .toBe(all.length);

    await testInfo.attach('opik.source-mix-traces', {
      body: JSON.stringify({ projectId: project.id, all, countedIds, excludedIds }, null, 2),
      contentType: 'application/json',
    });

    await use({
      all,
      countedIds,
      excludedIds,
      expectedTodayCount: countedIds.length,
    });
  },
});

export { expect } from './openrouter-repriced-spans.fixture';
