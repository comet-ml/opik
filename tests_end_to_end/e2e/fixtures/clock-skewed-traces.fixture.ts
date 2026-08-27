import { test as baseTest } from './export-comparison.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import { uuid7 } from '../core/backend';

/**
 * A producer whose clock is wrong by centuries is not hypothetical: the
 * partition-pruning Javadoc names litellm stamping traces in ~2201. Fixed
 * rather than "now + N years" so the id lands in the same ClickHouse partition
 * on every run.
 */
const FAR_FUTURE = new Date('2201-03-04T05:06:07.000Z');

/** Comfortably outside the Logs table's default "Past 30 days" window. */
const AGED_DAYS = 400;

export interface ClockSkewedTraceRef {
  id: string;
  name: string;
  /** The instant embedded in the trace's UUIDv7 id, which is what reads window on. */
  moment: Date;
}

export interface ClockSkewedTracesRef {
  /** Two present-day traces the destructive tests delete. */
  ordinaryDoomed: ClockSkewedTraceRef[];
  /** A present-day trace nothing in the test touches. */
  ordinarySurvivor: ClockSkewedTraceRef;
  /** A trace stamped in the year 2201 — the class the delete must still reach. */
  futureDoomed: ClockSkewedTraceRef;
  /** A second year-2201 trace nothing touches, so an over-broad delete is visible. */
  futureSurvivor: ClockSkewedTraceRef;
  /** A trace stamped 400 days ago — the other side of the same partition boundary. */
  agedDoomed: ClockSkewedTraceRef;
  /** Every seeded trace, in seed order. */
  all: ClockSkewedTraceRef[];
  /** The three present-day traces, i.e. the ones the default Logs window renders. */
  withinDefaultLogsWindow: ClockSkewedTraceRef[];
}

export interface ClockSkewedTracesFixtures {
  clockSkewedTraces: ClockSkewedTracesRef;
}

/**
 * Six traces in one project whose UUIDv7 ids span three ClickHouse partitions:
 * now, the year 2201, and 400 days ago.
 *
 * Opik reads the creation instant back out of the id, so the id — not
 * `start_time` — is what a partition predicate prunes on. Both are stamped to
 * the same moment here, which is what a producer with a skewed clock actually
 * writes.
 *
 * Ids are minted up front because `POST /v1/private/traces` answers 201 with no
 * body, and every assertion in these specs is a by-id lookup.
 *
 * Teardown deletes everything it seeded, including ids a test has already
 * removed: `POST /v1/private/traces/delete` is idempotent, and traces do not
 * cascade with the project.
 */
export const test = baseTest.extend<ClockSkewedTracesFixtures>({
  clockSkewedTraces: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const now = new Date();
    const aged = new Date(now.getTime() - AGED_DAYS * 24 * 60 * 60 * 1000);

    const seeds: Array<{ key: string; moment: Date }> = [
      { key: 'ordinary-doomed-1', moment: now },
      { key: 'ordinary-doomed-2', moment: now },
      { key: 'ordinary-survivor', moment: now },
      { key: 'future-doomed', moment: FAR_FUTURE },
      { key: 'future-survivor', moment: FAR_FUTURE },
      { key: 'aged-doomed', moment: aged },
    ];

    const created: ClockSkewedTraceRef[] = [];
    for (const seed of seeds) {
      const name = `${testNamespace}-${seed.key}`;
      const id = await backendClient.createTraceWithSource({
        id: uuid7(seed.moment),
        projectName: project.name,
        name,
        source: 'sdk',
        input: { question: seed.key },
        output: { answer: seed.key },
        startTime: seed.moment,
        endTime: seed.moment,
      });
      created.push({ id, name, moment: seed.moment });
    }

    const [doomedA, doomedB, ordinarySurvivor, futureDoomed, futureSurvivor, agedDoomed] = created;

    const ref: ClockSkewedTracesRef = {
      ordinaryDoomed: [doomedA, doomedB],
      ordinarySurvivor,
      futureDoomed,
      futureSurvivor,
      agedDoomed,
      all: created,
      withinDefaultLogsWindow: [doomedA, doomedB, ordinarySurvivor],
    };

    await testInfo.attach('opik.clockSkewedTraces', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteTraces(created.map((trace) => trace.id));
      } catch (err) {
        console.warn(`[clockSkewedTraces fixture] delete warning for ${testNamespace}:`, err);
      }
    }
  },
});

export { expect } from './export-comparison.fixture';
