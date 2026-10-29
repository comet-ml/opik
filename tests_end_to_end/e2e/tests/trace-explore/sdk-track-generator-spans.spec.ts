import { test, expect } from '@e2e/fixtures';
import type { TrackedSpanRef } from '@e2e/core/backend';
import { LogsPage } from '@e2e/pom/logs.page';

/**
 * What the generator yields, and the only source of the expected outputs below.
 *
 * Four distinct items so a partial read is distinguishable from a whole one in
 * both directions: taking one, taking two and taking all four are three
 * different strings, so a decorator that recorded the wrong slice cannot land on
 * the right answer by accident.
 */
const ITEMS = ['alfa', 'bravo', 'charlie', 'delta'] as const;

/**
 * The shapes driven, and what each must end up recording.
 *
 * `take` is how many items the consumer reads before stopping; `expectedOutput`
 * is what the decorator records for that — `"".join(str(item) for item in
 * consumed)`, per `_try_aggregate_items`. Spelled as a slice of `ITEMS` rather
 * than as a literal so the two cannot drift apart.
 */
const SHAPES: Array<{
  label: string;
  shape: 'break_after' | 'bare_next' | 'islice' | 'consumer_raises' | 'exhaust' | 'plain_function';
  take?: number;
  expectedConsumed: readonly string[];
  /** Whether the consumer is expected to have caught something of its own. */
  caught: string | null;
  /** False for the two controls — the shapes that were already reported before the fix. */
  isEarlyExit: boolean;
}> = [
  {
    label: 'break-after-one',
    shape: 'break_after',
    take: 1,
    expectedConsumed: ITEMS.slice(0, 1),
    caught: null,
    isEarlyExit: true,
  },
  {
    label: 'bare-next',
    shape: 'bare_next',
    expectedConsumed: ITEMS.slice(0, 1),
    caught: null,
    isEarlyExit: true,
  },
  {
    label: 'islice-two',
    shape: 'islice',
    take: 2,
    expectedConsumed: ITEMS.slice(0, 2),
    caught: null,
    isEarlyExit: true,
  },
  {
    label: 'consumer-raises',
    shape: 'consumer_raises',
    take: 1,
    expectedConsumed: ITEMS.slice(0, 1),
    caught: 'RuntimeError',
    isEarlyExit: true,
  },
  // The two controls. Both were reported before opik#8518 as well, so a run
  // where these are present and the four above are missing is a product
  // finding, and a run where NOTHING is present is the SDK failing to reach
  // this environment. Without them the spec cannot tell those apart.
  {
    label: 'exhausted-control',
    shape: 'exhaust',
    expectedConsumed: ITEMS,
    caught: null,
    isEarlyExit: false,
  },
  {
    label: 'plain-function-control',
    shape: 'plain_function',
    // The plain function returns the whole joined string as its single
    // "consumed" item, so the expected output is the same text by a different
    // route — which is the point of it as a control.
    expectedConsumed: [ITEMS.join('')],
    caught: null,
    isEarlyExit: false,
  },
];

/** The output the decorator records for a given consumed sequence. */
const joined = (consumed: readonly string[]): string => consumed.join('');

/** How long the traces and spans may take to become queryable after the flush. */
const VISIBLE_TIMEOUT_MS = 120_000;

/**
 * How long the span count must STAY at the expected number before it counts as
 * the whole population.
 *
 * `expect.poll` succeeds on its first matching read, so without this a world
 * where the decorator opened a second span per generator would pass the instant
 * the count passed through six on its way up.
 */
const QUIET_MS = 10_000;

/**
 * `@opik.track` on a generator the consumer stops reading early (opik#8518).
 *
 * Before the fix, a partially consumed generator never raised `StopIteration`,
 * which was the only thing that ended the span opened on the first `next()` —
 * so nothing ended it and **the whole trace was dropped**. From the user's side
 * the SDK logged nothing at all, for code that looks completely ordinary:
 *
 *     for chunk in tracked_generator():
 *         break
 *
 * Stopping early is the normal way to read a streamed response, so this is a
 * default path, and losing a whole trace silently is the worst shape of failure
 * there is — there is no error to notice and nothing on screen to be suspicious
 * of.
 *
 * The estate could not have caught it. `opik-sdk-driver` drove `@opik.track`
 * for real but only on a plain function (`routes/traces.py`), so no generator
 * and no early exit had ever run; the nearest spec,
 * `sdk-track-non-error-throw.spec.ts`, is the TYPESCRIPT SDK on a different
 * failure. This adds the `/traces/track-generator` route the driver was missing.
 *
 * Two things make the assertions more than "a trace exists":
 *
 *  - **The recorded output is the PARTIAL output.** The fix records what was
 *    actually yielded before the consumer stopped, so each shape has its own
 *    expected string and a decorator that recorded the whole generator, or
 *    nothing, fails. Asserted on the trace AND on its span, because they are
 *    ended by the same callback and a build that ended only one of them would
 *    leave a trace with no span or a span with no trace.
 *  - **The population is asserted by exhaustion.** Exactly six traces and six
 *    spans in the project, the label set compared whole, so an extra span per
 *    generator or a missing one both fail — rather than "the six I looked for
 *    were there, among however many".
 *
 * Deterministic: no LLM, no wall clock, and the generator yields a fixed list.
 * Duration is asserted as "present and positive", never as a value.
 *
 * NOTE on which SDK this drives. The bridge resolves `opik` from this checkout
 * (`[tool.uv.sources]` in its pyproject), which on this branch is `main` with
 * the release merged in — so the code under test is main's SDK, not the 2.2.93
 * wheel. That is the estate's deliberate choice (a released wheel cannot show a
 * regression in code that has not shipped), and opik#8518 is in 2.2.93, so the
 * behaviour pinned here is present in both.
 */
test.describe(
  'Trace Explore — @track on a generator that is never exhausted',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test.setTimeout(300_000);

    test(
      'every way of stopping a tracked generator early still logs its trace, its span and what it yielded',
      { tag: ['@cap:traces.sdk-track-generator-spans'] },
      async ({ project, sdkClient, backendClient, registerProjectTracesCleanup }) => {
        // Registered BEFORE anything is written. The decorator mints the trace
        // ids inside the SDK so this test never sees them, and a project delete
        // does not cascade to traces — so without this the rows outlive the
        // run. Registered first rather than after the write, so a bridge call
        // that failed half way through still has its traces swept.
        registerProjectTracesCleanup(project.id);

        const result = await test.step(
          'Drive all six shapes through the SDK bridge in one flush',
          () =>
            sdkClient.python.trackedGeneratorCalls({
              project_name: project.name,
              items: [...ITEMS],
              calls: SHAPES.map(({ label, shape, take }) => ({
                label,
                shape,
                ...(take === undefined ? {} : { take }),
              })),
            }),
        );

        const byLabel = new Map(result.calls.map((call) => [call.label, call]));

        await test.step('Each consumer really stopped where it was told to', async () => {
          // The premise every assertion below rests on, and it can only be
          // checked from inside the bridge's process: if a consumer had read
          // more items than it meant to, the expected output for that shape
          // would be wrong and a span recording the WHOLE generator would pass.
          expect(
            SHAPES.map(({ label }) => `${label}=${byLabel.get(label)?.consumed.join(',')}`),
            'every shape consumed exactly the items it was supposed to',
          ).toEqual(
            SHAPES.map(({ label, expectedConsumed }) => `${label}=${expectedConsumed.join(',')}`),
          );
        });

        await test.step("The consumer_raises shape really did raise in the consumer's loop", async () => {
          // Without this the shape degenerates into `break_after` and proves
          // nothing extra. It matters because the generator itself did NOT
          // fail: the span must read as a partial success, which is asserted
          // further down as the absence of an error on it.
          expect(
            SHAPES.map(({ label }) => `${label}=${byLabel.get(label)?.caught}`),
            'only the consumer_raises shape caught anything, and it caught a RuntimeError',
          ).toEqual(SHAPES.map(({ label, caught }) => `${label}=${caught}`));
        });

        const spans = await test.step(
          'The project holds exactly one span per call, and no more',
          async () => {
            let seen: TrackedSpanRef[] = [];
            await expect
              .poll(
                async () => {
                  seen = await backendClient.listTrackedSpans({ projectId: project.id });
                  return seen.length;
                },
                {
                  message:
                    'one span per tracked call — before opik#8518 the four early-exit ' +
                    'shapes contributed none at all',
                  timeout: VISIBLE_TIMEOUT_MS,
                  intervals: [2_000, 3_000, 5_000],
                },
              )
              .toBe(SHAPES.length);

            // Exact, and held: a count read on its way up would pass against a
            // build that opened a second span per generator.
            const firstIds = seen.map((span) => span.id).sort();
            await new Promise((resolve) => setTimeout(resolve, QUIET_MS));
            seen = await backendClient.listTrackedSpans({ projectId: project.id });
            expect(
              seen.map((span) => span.id).sort(),
              'the span population is the same set after a quiet period',
            ).toEqual(firstIds);
            return seen;
          },
        );

        await test.step('Those spans are exactly the six calls, one root span each', async () => {
          expect(
            spans.map((span) => span.name).sort(),
            'the span names are exactly the labels driven — no shape missing, none doubled',
          ).toEqual(SHAPES.map(({ label }) => label).sort());
          expect(
            spans.filter((span) => span.parentSpanId !== null).map((span) => span.name),
            '@track on a top-level call produces a ROOT span, with no parent',
          ).toEqual([]);
          // On the SPAN as well as on the trace, which the per-shape loop below
          // asserts. The two are written separately, so a decorator that blamed
          // the CONSUMER's exception on the generator it was reading could leave
          // the trace clean and stamp only the span — and `consumer_raises`
          // would then pass as the partial success it is supposed to prove.
          expect(
            spans
              .filter((span) => span.errorInfo !== null)
              .map((span) => `${span.name}=${span.errorInfo?.exceptionType}`),
            'no span carries an error — in every shape the generator itself succeeded',
          ).toEqual([]);
        });

        const spanByName = new Map(spans.map((span) => [span.name, span]));

        await test.step('Each span records what its consumer actually read', async () => {
          // The silent-wrongness half. Presence alone would be satisfied by a
          // build that ended every span with the whole generator's output, or
          // with none — both of which misreport what happened.
          expect(
            SHAPES.map(({ label }) => {
              const output = spanByName.get(label)?.output as
                | { output?: unknown }
                | null
                | undefined;
              return `${label}=${String(output?.output)}`;
            }),
            'every span carries the consumed items joined, and nothing else',
          ).toEqual(
            SHAPES.map(({ label, expectedConsumed }) => `${label}=${joined(expectedConsumed)}`),
          );
        });

        await test.step('And so does each trace, with a real duration and one span', async () => {
          for (const { label, expectedConsumed } of SHAPES) {
            const span = spanByName.get(label);
            // Asserted present before use: a `?.` here would turn a missing
            // span — the whole pre-fix regression — into a silent skip of
            // every assertion in this loop.
            expect(span, `a span named '${label}' must exist`).toBeDefined();
            const traceId = span!.traceId;

            const payload = await backendClient.getTracePayload(traceId);
            expect(payload, `the trace behind span '${label}' must exist`).not.toBeNull();
            expect(payload!.name, 'the trace is named for the tracked function').toBe(label);
            expect(
              (payload!.output as { output?: unknown } | null)?.output,
              `the trace for '${label}' records the same consumed items as its span`,
            ).toBe(joined(expectedConsumed));

            const duration = await backendClient.getTraceDuration(traceId);
            // Non-null AND positive, which together are "the trace was ended":
            // duration is derived from end_time, so a span left open leaves it
            // null — and that null is what the Logs table renders as "NA".
            expect(
              duration,
              `the trace for '${label}' was ended, so it has a duration rather than null`,
            ).not.toBeNull();
            expect(duration!, 'and that duration is a real elapsed time').toBeGreaterThan(0);

            // The partial-success half, and the reason `consumer_raises` is a
            // shape of its own rather than a second `break_after`: the exception
            // is raised in the CONSUMER's loop body and caught outside it, so
            // the generator never fails and the trace must carry no error at
            // all. A decorator that attributed the consumer's exception to the
            // generator it was reading would report a failed trace for code
            // that worked, which is the mirror image of
            // `sdk-track-non-error-throw.spec.ts` — and asserted on every shape,
            // so an error stamped on the plain early exits fails here too.
            const lifecycle = await backendClient.getTraceLifecycle(traceId);
            expect(lifecycle, `the trace for '${label}' must be readable`).not.toBeNull();
            expect(
              lifecycle!.endTime,
              `the trace for '${label}' must have been closed by the decorator`,
            ).not.toBeNull();
            expect(
              lifecycle!.errorInfo,
              `the trace for '${label}' records no error — the generator itself did not fail`,
            ).toBeNull();

            const traceSpans = await backendClient.listSpanRefs({
              projectId: project.id,
              traceId,
            });
            expect(
              traceSpans.map((ref) => ref.name),
              `the trace for '${label}' holds exactly its own one span`,
            ).toEqual([label]);
          }
        });
      },
    );

    test(
      'the early-exit traces render in the Logs table with a numeric duration, not "NA"',
      { tag: ['@cap:traces.sdk-track-generator-spans'] },
      async ({ project, sdkClient, backendClient, page, registerProjectTracesCleanup }) => {
        registerProjectTracesCleanup(project.id);

        // The user-visible half, and the reason this is worth a UI test at all:
        // the pre-fix symptom was "my trace isn't in the list", and the
        // half-fixed symptom — a trace that is listed but never ended — renders
        // as a duration cell reading "NA". Both are things a reader sees on this
        // page and nowhere else.
        const result = await test.step('Drive all six shapes through the SDK bridge', () =>
          sdkClient.python.trackedGeneratorCalls({
            project_name: project.name,
            items: [...ITEMS],
            calls: SHAPES.map(({ label, shape, take }) => ({
              label,
              shape,
              ...(take === undefined ? {} : { take }),
            })),
          }));
        expect(
          result.calls.map((call) => call.label).sort(),
          'the bridge made every call before anything is read back',
        ).toEqual(SHAPES.map(({ label }) => label).sort());

        const traceIdByLabel = await test.step(
          'Wait for all six traces to be queryable, and map them to their labels',
          async () => {
            let spans: TrackedSpanRef[] = [];
            await expect
              .poll(
                async () => {
                  spans = await backendClient.listTrackedSpans({ projectId: project.id });
                  return spans.length;
                },
                {
                  message: 'one span per tracked call before the browser is opened',
                  timeout: VISIBLE_TIMEOUT_MS,
                  intervals: [2_000, 3_000, 5_000],
                },
              )
              .toBe(SHAPES.length);
            // A UI assertion over a seed that only half landed is a test that
            // cannot fail, so the map is built from an asserted-complete set.
            const map = new Map(spans.map((span) => [span.name, span.traceId]));
            expect(
              [...map.keys()].sort(),
              'every label resolved to exactly one trace',
            ).toEqual(SHAPES.map(({ label }) => label).sort());
            return map;
          },
        );

        const logs = new LogsPage(page);

        await test.step('Open the project Logs on the Traces tab', async () => {
          // Both stated rather than inherited: `logsType`, the date range and
          // the page size are each persisted per project, so an unstated one is
          // whatever the browser profile last held.
          await logs.gotoTraces(project.id, { timeRange: 'alltime', size: 100 });
          await logs.waitForReady();
          expect(await logs.activeLogsTab(), 'the Traces tab is the one on screen').toBe('traces');
        });

        await test.step('Every tracked call is a row in the table, and only those', async () => {
          await expect
            .poll(() => logs.readPaginationTotal(), {
              message: 'the footer reports one trace per tracked call',
              timeout: 60_000,
            })
            .toBe(SHAPES.length);
          // The footer comes from the listing's envelope, so it can be right
          // while the table body is still painting. Settle on the row COUNT
          // with an auto-retrying locator assertion before reading the ids
          // once — otherwise a slow render reads as a wrong row set.
          await expect(
            logs.traceRows,
            'the table body paints one row per trace',
          ).toHaveCount(SHAPES.length);
          expect(
            (await logs.readRowIdsOnPage()).sort(),
            'the rendered rows are exactly the traces the six calls wrote',
          ).toEqual([...traceIdByLabel.values()].sort());
        });

        await test.step('And each one shows an elapsed time rather than "NA"', async () => {
          for (const { label, isEarlyExit } of SHAPES) {
            const traceId = traceIdByLabel.get(label);
            expect(traceId, `a trace for '${label}' must be on screen`).toBeDefined();
            const cell = logs.durationCell(traceId!);
            await expect(
              cell,
              `exactly one duration cell for '${label}'`,
            ).toHaveCount(1);
            // A number with a unit, which is what a closed trace renders. "NA"
            // is the literal the cell shows for a null duration, i.e. a span
            // that was opened and never ended — so matching a number here is
            // the same assertion as the API-level `not.toBeNull()`, made where
            // a user would actually notice it.
            //
            // An auto-retrying `toHaveText` rather than a one-shot
            // `textContent()`: the cell is painted from the same fetch the
            // footer came from, so a read taken the instant the row appears can
            // catch it empty — and an empty cell is not "NA", so a one-shot
            // read would fail for timing with a message about the wrong thing.
            await expect(
              cell,
              `the duration cell for '${label}'${isEarlyExit ? ' (an early exit)' : ' (a control)'} ` +
                'shows an elapsed time, not "NA"',
            ).toHaveText(/^\d+(\.\d+)?\s*\S+$/);
            const text = ((await cell.textContent()) ?? '').trim();
            expect(text, 'and is not the null sentinel').not.toBe('NA');
          }
        });
      },
    );
  },
);
