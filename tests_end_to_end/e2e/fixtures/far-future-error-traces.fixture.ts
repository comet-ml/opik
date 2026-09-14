import { test as baseTest } from './span-kpi-cards.fixture';
import { isUuidWindowRejection, UUID_VALIDATION_SKIP_REASON } from './id-aged-traces.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7, type BackendClient } from '../core/backend';

export interface FarFutureErrorTracesRef {
  /** Every seeded trace id, including the far-future one. */
  traceIds: string[];
  /** The one trace whose UUIDv7 id embeds mid-2200. */
  farFutureTraceId: string;
  /** What a 30-day windowed read of `/projects/stats` must answer. */
  windowed: { traceCount: number; errorCount: number };
  /**
   * What an unwindowed read must answer.
   *
   * `traceCount` gains the far-future row, because that count honours the
   * caller's window and there now is none. `errorCount` does NOT: the error
   * stat carries its own bounds — `TraceDAO` sums errors whose id-derived
   * instant is before `now64(9)`, split at `startOfDay(now - 7d)` — so it is
   * blind to the requested window and a far-future id is outside it either way.
   * That upper bound is the one the release corrected.
   */
  unwindowed: { traceCount: number; errorCount: number };
}

export interface FarFutureErrorTracesFixtures {
  farFutureErrorTraces: FarFutureErrorTracesRef;
}

const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * Above the 16-bit `Date` ceiling (2149-06-06), the age at which a trace's week
 * bound used to wrap back into range and count the row as recent. This is the
 * live gap the release closed, and the age `projects-list-stats.spec.ts` — the
 * existing guard on these same columns — never seeds.
 */
const FAR_FUTURE_MOMENT = new Date(Date.UTC(2200, 5, 15));

/** Errored, inside the rolling 30-day window and inside the last week. */
const RECENT_ERROR_AGE_DAYS = [1, 2, 3, 4, 5];
/** Errored, still inside the 30-day window but outside the last week. */
const OLDER_ERROR_AGE_DAYS = [12, 15, 18];
/** Clean, recent — so trace count and error count cannot be the same number. */
const CLEAN_AGE_DAYS = [1.5, 2.5];

const ERROR_INFO = {
  exceptionType: 'ValueError',
  message: 'seeded trace failure',
  traceback: 'seeded trace failure',
};

/** The 48-bit big-endian millisecond timestamp a UUIDv7 carries, per RFC 9562. */
const embeddedMillis = (id: string): number =>
  parseInt(id.replace(/-/g, '').slice(0, 12), 16);

/**
 * Eleven traces in one fresh project: eight errored and two clean inside the
 * rolling 30-day window, plus one errored trace whose UUIDv7 id embeds mid-2200.
 *
 * What the two reads must answer:
 *
 *                  traces   errors
 *   30-day window      10        8
 *   unwindowed         11        8
 *
 * Traces and errors deliberately never coincide, so a read that dropped its
 * window cannot satisfy both by accident. The unwindowed trace count doubles as
 * proof the far-future trace was really written — without it, "excluded from
 * the count" and "never seeded" would be the same observation.
 *
 * The error count is 8 under both reads on purpose, and is not an oversight:
 * the error stat is bounded by the server rather than by the caller (see
 * `unwindowed` below), so the far-future row is outside it whatever window is
 * asked for.
 *
 * Deliberately silent on the deviation percentage the Errors column renders
 * beside the count: `StatsMapper.getStatsErrorCount` divides two longs before
 * scaling by 100, so the value can only ever be a multiple of 100 and reads 0
 * whenever this week is quieter than last. Asserting the honest number would
 * fail until that is fixed; asserting the truncated one would pin the bug in
 * place. See the spec's header.
 */
export const test = baseTest.extend<FarFutureErrorTracesFixtures>({
  farFutureErrorTraces: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const traceIds: string[] = [];

    const seed = async (label: string, ageDays: number, errored: boolean): Promise<string> => {
      const moment = new Date(Date.now() - ageDays * DAY_MS);
      return seedAt(label, moment, errored);
    };

    const seedAt = async (label: string, moment: Date, errored: boolean): Promise<string> => {
      const id = uuid7(moment);
      await backendClient.createTraceWithSource({
        id,
        projectName: project.name,
        name: `${testNamespace}-${label}`,
        source: 'sdk',
        input: `${testNamespace} input ${label}`,
        output: `${testNamespace} output ${label}`,
        // start_time follows the id for the dated rows so the seed reads
        // coherently; the window itself is applied to the id.
        startTime: moment,
        endTime: new Date(moment.getTime() + 1_000),
        ...(errored ? { errorInfo: ERROR_INFO } : {}),
      });
      traceIds.push(id);
      return id;
    };

    try {
      for (const [i, age] of RECENT_ERROR_AGE_DAYS.entries()) {
        await seed(`recent-error-${i}`, age, true);
      }
      for (const [i, age] of OLDER_ERROR_AGE_DAYS.entries()) {
        await seed(`older-error-${i}`, age, true);
      }
      for (const [i, age] of CLEAN_AGE_DAYS.entries()) {
        await seed(`clean-${i}`, age, false);
      }

      let farFutureTraceId: string;
      try {
        // `start_time` stays at "now" for this one: the id is the axis under
        // test, and a 2200 start_time would additionally exercise the write
        // path's own range validation.
        const id = uuid7(FAR_FUTURE_MOMENT);
        const embedded = embeddedMillis(id);
        if (embedded !== FAR_FUTURE_MOMENT.getTime()) {
          throw new Error(
            `[farFutureErrorTraces fixture] id ${id} embeds ${new Date(embedded).toISOString()}, ` +
              `expected ${FAR_FUTURE_MOMENT.toISOString()}`,
          );
        }
        await backendClient.createTraceWithSource({
          id,
          projectName: project.name,
          name: `${testNamespace}-far-future-error`,
          source: 'sdk',
          input: `${testNamespace} input far-future`,
          output: `${testNamespace} output far-future`,
          endTime: new Date(),
          errorInfo: ERROR_INFO,
        });
        traceIds.push(id);
        farFutureTraceId = id;
      } catch (err) {
        // Reject-mode UUID validation refuses exactly the id this fixture
        // exists to seed. It ships disabled and the mode is not readable from
        // the client, so it is detected from the rejection rather than checked
        // up front — otherwise the spec fails as an opaque 400 that reads like
        // a product bug.
        if (isUuidWindowRejection(err)) baseTest.skip(true, UUID_VALIDATION_SKIP_REASON);
        throw err;
      }

      const windowedTraces =
        RECENT_ERROR_AGE_DAYS.length + OLDER_ERROR_AGE_DAYS.length + CLEAN_AGE_DAYS.length;
      const windowedErrors = RECENT_ERROR_AGE_DAYS.length + OLDER_ERROR_AGE_DAYS.length;

      const ref: FarFutureErrorTracesRef = {
        traceIds: [...traceIds],
        farFutureTraceId,
        windowed: { traceCount: windowedTraces, errorCount: windowedErrors },
        unwindowed: { traceCount: windowedTraces + 1, errorCount: windowedErrors },
      };

      await testInfo.attach('opik.farFutureErrorTraces', {
        body: JSON.stringify(ref, null, 2),
        contentType: 'application/json',
      });

      await use(ref);
    } finally {
      if (!shouldLeaveArtifacts(testInfo) && traceIds.length > 0) {
        await deleteSeeded(backendClient, traceIds);
      }
    }
  },
});

/**
 * Deleting the project would not take these with it, and `global-teardown`'s
 * run-prefix sweep does not know about traces at all — so they are deleted
 * explicitly, and never by throwing: a cleanup failure must not replace the
 * test's own error.
 */
async function deleteSeeded(backendClient: BackendClient, traceIds: string[]): Promise<void> {
  try {
    await backendClient.deleteTraces(traceIds);
    return;
  } catch (err) {
    console.warn('[farFutureErrorTraces fixture] batch delete failed, retrying per id:', err);
  }
  for (const id of traceIds) {
    try {
      await backendClient.deleteTraces([id]);
    } catch (err) {
      console.warn(`[farFutureErrorTraces fixture] could not delete ${id}:`, err);
    }
  }
}

export { expect } from './span-kpi-cards.fixture';
