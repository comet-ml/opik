import { test, expect } from '@e2e/fixtures';
import { track, flushAll } from 'opik';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * A function wrapped in the TypeScript SDK's `track` must re-throw the caller's
 * own value and still close its trace, even when what was thrown is not an
 * `Error` (OPIK-7791, opik#8422).
 *
 * The failure this guards is silent at both ends. `logError` describes whatever
 * was thrown before re-throwing it, and describing a non-`Error` is exactly
 * where that can itself throw — reading `.message` off `null`, or `String()`-ing
 * a value with no path to a primitive. An exception raised there runs instead of
 * the re-throw and instead of both `end()` calls, so:
 *
 *   - the caller catches an `Error` it never threw, losing the original value, and
 *   - the trace is submitted with no `endTime` and sits open in the Logs table
 *     forever, rendering "NA" for its duration while otherwise looking normal.
 *
 * Three throws, chosen because they fail `toErrorInfo` in different places:
 * `null` (no properties to read), a null-prototype object (`String()` throws —
 * no `Symbol.toPrimitive`, no `toString`), and an object whose `toString` throws
 * of its own accord. All three are deterministic: no LLM, no wall clock.
 *
 * Both surfaces, in the direction that catches a disagreement — the SDK writes,
 * the API and then the Logs table read back. The decorator is the subject and it
 * is otherwise e2e-dark: the estate's only other TypeScript SDK use is its
 * generated REST client.
 *
 * NOTE ON THE TAG: `@cap:traces.list-traces` is what this spec's Logs-table
 * assertions exercise, and it is the nearest key the taxonomy has. The real
 * subject — the SDK trace lifecycle and its error path — has no capability of
 * its own, and no SDK area exists to hang one on. Worth raising with
 * taxonomy-discovery rather than papering over here.
 */

/** One non-`Error` throw, and why describing it is hard. */
const THROWS: Array<{ label: string; make: () => unknown }> = [
  { label: 'null', make: () => null },
  // No prototype, so no `toString` and no `Symbol.toPrimitive`: `String(value)`
  // raises "Cannot convert object to primitive value".
  { label: 'null-prototype-object', make: () => Object.create(null) },
  // A prototype chain that exists and actively misbehaves — the other way the
  // describe step can raise.
  {
    label: 'throwing-tostring',
    make: () => ({
      toString() {
        throw new Error('toString is not available on this value');
      },
    }),
  },
];

test.describe('TypeScript SDK @track — non-Error throws', { tag: ['@t2-cuj', '@area:traces'] }, () => {
  test('a non-Error thrown from a tracked function reaches the caller unchanged and still closes its trace', { tag: ['@cap:traces.list-traces'] }, async ({
    project,
    envConfig,
    testNamespace,
    backendClient,
    page,
  }) => {
    test.setTimeout(180_000);

    expect(
      envConfig.apiKey,
      'the SDK needs an API key — global-setup mints one into OPIK_API_KEY',
    ).toBeTruthy();

    // `track` does not take a client: it builds its own `OpikClient()` from the
    // environment on first use and caches it for the life of the worker. The
    // key and workspace are already in the worker's env (global-setup), so only
    // the URL has to be pointed at the deployment under test — and it must be
    // set before the first tracked call, not after.
    process.env.OPIK_URL_OVERRIDE = envConfig.apiBaseUrl;

    const names = THROWS.map(({ label }) => `${testNamespace}-track-${label}`);

    await test.step('Each tracked function re-throws the caller its own value, identically', async () => {
      for (const [index, { label, make }] of THROWS.entries()) {
        const thrown = make();
        const tracked = track({ name: names[index], projectName: project.name }, async () => {
          throw thrown;
        });

        let caught: unknown;
        let threw = false;
        try {
          await tracked();
        } catch (err) {
          threw = true;
          caught = err;
        }

        expect(threw, `the tracked function must still throw for ${label}`).toBe(true);
        // Object.is rather than toBe/toEqual: the assertion is identity, and
        // two of these three values cannot be rendered into a failure message
        // without raising again. Comparing booleans keeps the reporter safe.
        expect(
          Object.is(caught, thrown),
          `the caller must catch the very value it threw for ${label}, not a replacement Error`,
        ).toBe(true);
      }
    });

    await test.step('Flush the SDK so all three traces are submitted', async () => {
      await flushAll();
    });

    const lifecycles = await test.step('All three traces land, closed and carrying an error', async () => {
      // Polled on the whole set rather than read once: ingestion is
      // asynchronous, and a spec that read after the first trace appeared could
      // pass while the other two were still in flight.
      await expect
        .poll(async () => (await backendClient.listTraceIds({ projectId: project.id })).length, {
          message: 'the project holds all three tracked traces',
          timeout: 60_000,
          intervals: [500, 1_000, 2_000],
        })
        .toBe(THROWS.length);

      const ids = await backendClient.listTraceIds({ projectId: project.id });
      const byName = new Map<string, { id: string; endTime: string | null }>();
      for (const id of ids) {
        const lifecycle = await backendClient.getTraceLifecycle(id);
        expect(lifecycle, `trace ${id} must be readable`).not.toBeNull();

        // The trace was CLOSED. This is the assertion the whole spec exists
        // for: an open trace still lists, still opens, and differs from a
        // finished one in this field alone.
        expect(
          lifecycle!.endTime,
          `${lifecycle!.name} must have been closed by the decorator`,
        ).not.toBeNull();

        // And it reports what went wrong. `exceptionType` is pinned because a
        // coerced non-Error is described as a plain "Error"; the message is
        // asserted non-empty rather than verbatim, since its exact wording is
        // the SDK's own and may legitimately change.
        expect(lifecycle!.errorInfo, `${lifecycle!.name} must carry error info`).not.toBeNull();
        expect(
          lifecycle!.errorInfo!.exceptionType,
          `exception type of ${lifecycle!.name}`,
        ).toBe('Error');
        expect(
          lifecycle!.errorInfo!.message ?? '',
          `${lifecycle!.name} must describe what was thrown`,
        ).not.toBe('');

        byName.set(lifecycle!.name, { id: lifecycle!.id, endTime: lifecycle!.endTime });
      }

      // The whole answer, not just that each name is somewhere in it: the
      // project must hold these three traces and no fourth.
      expect([...byName.keys()].sort(), 'exactly the three tracked traces').toEqual(
        [...names].sort(),
      );
      return byName;
    });

    const logs = new LogsPage(page);

    await test.step('The Logs table renders all three as finished, with a duration rather than NA', async () => {
      await logs.goto(project.id);
      await logs.waitForReady();
      await expect(logs.traceRows).toHaveCount(THROWS.length);

      for (const name of names) {
        const seen = lifecycles.get(name);
        expect(seen, `${name} must have been read back from the API`).toBeDefined();
        const cell = logs.durationCell(seen!.id);
        await expect(cell).toHaveCount(1);
        // "NA" is what the table shows for a trace that was never closed —
        // asserting a numeric duration is what tells the two apart.
        await expect(cell).toHaveText(/^\s*\d/);
      }
    });
  });
});
