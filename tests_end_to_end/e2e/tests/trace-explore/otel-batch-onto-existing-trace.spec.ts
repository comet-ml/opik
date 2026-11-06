import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import type { BackendClient } from '@e2e/core/backend';
import { randomBytes } from 'node:crypto';

/**
 * A later OTLP export carrying a top-level span for a trace an earlier export
 * already created lands on that trace instead of being dropped (opik#9312 /
 * OPIK-9053).
 *
 * OTel exporters batch by TIME, not by trace. A span tree that outlives one
 * batch window therefore arrives as several exports sharing one OTLP trace id,
 * and more than one of them can carry a parentless span —
 * `OpenTelemetryService` treats every parentless span as a trace root and tries
 * to create a trace for it. Before this release that second attempt was fatal:
 *
 *  - one conflicting root answered `409`, which the OTLP spec tells an exporter
 *    not to retry, so the whole batch was discarded;
 *  - several conflicting roots in one batch answered `500` — `traceService`
 *    collected the per-root errors into a `CompositeException` — which an
 *    exporter DOES retry, forever, each attempt failing the same way.
 *
 * Either way the spans were never stored. That is silent production data loss
 * for the most ordinary Collector configuration there is, and the only signal is
 * a trace tree that is missing its later half.
 *
 * The fix resumes an `EntityAlreadyExistsException` from the root create and
 * goes on to store the batch's spans. Its own comment is explicit that the
 * cross-project case must NOT be resumed — a trace belonging to another project
 * stays an `IdentifierMismatchException` — so the second test here is that
 * refusal, which is the thing the fix could plausibly have broken.
 *
 * ## Why the estate's one OTel routing spec cannot cover this
 *
 * `otel-project-name-routing.spec.ts` deliberately gives every span of a
 * multi-destination export its OWN OTLP trace id, to avoid a known
 * intermittent collision (its header explains why; the taxonomy note records
 * it as deliberately uncovered). As a result no spec anywhere sends a SECOND
 * export onto a trace that already exists — which is this collision's only
 * deterministic form and the one a real Collector produces constantly.
 *
 * ## Why this is deterministic where that one is not
 *
 * The race that spec avoids is several roots of ONE export fanning out to
 * several projects, where which write lands first is up to a `flatMap`. Nothing
 * here fans out. `OpenTelemetryService` maps an OTLP trace id to an Opik trace
 * id through Redis under a key that includes the project, so every export into
 * the SAME project reuses the first one's mapping and the exports are ordered
 * by the test itself, one awaited request at a time.
 *
 * The cross-project test needs one more thing to be a shape rather than a coin
 * toss, and it is not the project: because the Redis key is per project, a
 * second project derives the trace id afresh from
 * `convertOtelIdToUUIDv7(otelTraceId, minStartMs)`. The two derived ids collide
 * only when the exports agree on the MILLISECOND as well as the OTLP id —
 * exploration measured that an export with ordinary wall-clock timestamps
 * collides with nothing and is harmlessly accepted — so both exports there pin
 * `startTimeMs` to one value. Without that the test would be asserting a 409
 * that mostly does not happen.
 *
 * ## Not claimed
 *
 * That a span carrying an explicit `opik.trace_id` for a trace in another
 * project is refused. Exploration found it is accepted and stored as an orphan,
 * believes the behaviour predates this PR (the root-span filter that skips
 * `overriddenTraceIds` is not in its diff), and reported it for a human call on
 * whether orphaning is intended. Pinning either answer here would be guessing
 * at a design decision nobody has made.
 */

/** A random OTLP trace id as lowercase hex — 16 bytes, as the protocol requires. */
const otlpTraceId = (): string => randomBytes(16).toString('hex');

/**
 * How long the Spans/Traces listings get to report a population, matching
 * `otel-project-name-routing.spec.ts`'s own constant and for its reason: these
 * reads share a per-workspace rate limit, and a shed fetch is answered with an
 * empty page rather than an error, so a short budget turns an infrastructure
 * blip into a failure that reads like data loss.
 */
const LISTING_SETTLE_MS = 90_000;

/**
 * The spans a project holds, once it holds exactly `expected` of them.
 *
 * Polls the envelope's `total` and then re-reads, so the rows returned and the
 * population asserted come from one answer. The COUNT is the assertion rather
 * than a lookup for the names expected: this spec's subject is spans that were
 * dropped, and a read that returned the expected ones beside an extra would
 * satisfy any per-name lookup.
 */
async function spansIn(
  backendClient: BackendClient,
  projectId: string,
  expected: number,
  what: string,
): Promise<Array<{ id: string; name: string; traceId: string }>> {
  await expect
    .poll(async () => (await backendClient.listProjectSpans({ projectId })).total, {
      timeout: LISTING_SETTLE_MS,
      intervals: [500, 1_000, 2_000],
      message: `${what} must hold exactly ${expected} span(s)`,
    })
    .toBe(expected);
  const page = await backendClient.listProjectSpans({ projectId });
  expect(page.total, `${what}: the envelope total`).toBe(expected);
  expect(page.spans, `${what}: the rows behind that total`).toHaveLength(expected);
  return page.spans;
}

/**
 * A project holds no span at all — asserted as a settled fact, not a first read.
 *
 * `toBe(0)` on a single read of a project a refused export might yet write into
 * passes before the write would have landed, so the assertion would be green
 * for the wrong reason. `toPass` over a window re-reads until the deadline,
 * which means a span had time to arrive and did not.
 */
async function staysEmpty(
  backendClient: BackendClient,
  projectId: string,
  what: string,
): Promise<void> {
  await expect(async () => {
    const page = await backendClient.listProjectSpans({ projectId });
    expect(page.total, `${what} must receive no span at all`).toBe(0);
  }).toPass({ timeout: 15_000, intervals: [1_000, 2_000, 2_000] });
}

test.describe(
  'OTel ingestion — a second export onto an existing trace',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'three exports sharing one OTLP trace id, each with top-level spans, all land on one trace',
      { tag: ['@cap:traces.otel-project-routing'] },
      async ({ backendClient, project, testNamespace, page }) => {
        // Five spans through three awaited exports, an ingestion poll, two
        // listing reads and a panel walk — more than the 90s default allows
        // once the workspace listing budget is being shed.
        test.setTimeout(240_000);

        const sharedTraceId = otlpTraceId();
        // One millisecond for all three exports. Not required for this test —
        // the Redis mapping is per project and these all name one project, so
        // the second and third exports reuse the first's trace id whatever
        // their timestamps — but pinned anyway so that "they landed on one
        // trace" is attributable to the mapping rather than to three
        // timestamps that happened to round the same way.
        const startTimeMs = Date.now();

        const firstBatch = `${testNamespace}-batch-1-root`;
        const secondBatch = `${testNamespace}-batch-2-root`;
        const thirdBatch = [0, 1, 2].map((i) => `${testNamespace}-batch-3-root-${i}`);
        const allSpanNames = [firstBatch, secondBatch, ...thirdBatch];

        const exportBatch = async (names: readonly string[], what: string): Promise<void> => {
          const { status, message } = await backendClient.postOtelExport({
            projectNameHeader: project.name,
            // Every span parentless and on the SHARED id — the two properties
            // that together make each export look like a new trace root for a
            // trace that already exists. No `opik.project_name`: routing is
            // not the subject, so the header decides the project for all three
            // and the Redis mapping is keyed on one project throughout.
            spans: names.map((name) => ({ name, otelTraceIdHex: sharedTraceId })),
            startTimeMs,
          });
          expect(
            status,
            `${what} must be accepted — a 409 here is the lone-conflict drop and a 500 is the ` +
              `CompositeException, which are the two failures opik#9312 fixed: ${message}`,
          ).toBe(200);
        };

        await test.step('The first export creates the trace', async () => {
          await exportBatch([firstBatch], 'the first export');
        });

        const traceId = await test.step('…and exactly one trace now exists for it', async () => {
          // Read before the second export goes out, so the conflict the rest
          // of this test is about is known to be a real one. Without this, a
          // backend that never created the first trace at all would make every
          // later export unremarkable — nothing to conflict with — and the
          // span counts below would still add up.
          await expect
            .poll(async () => (await backendClient.listTraceIds({ projectId: project.id })).length, {
              timeout: LISTING_SETTLE_MS,
              intervals: [500, 1_000, 2_000],
              message: 'the first export must create one trace before a second one can conflict',
            })
            .toBe(1);
          const traces = await backendClient.listTraceIds({ projectId: project.id });
          expect(traces, 'one trace from the first export').toHaveLength(1);
          return traces[0];
        });

        await test.step('A second export with one more top-level span is accepted', async () => {
          // The lone-conflict case: one parentless span for a trace that
          // exists, which used to be the unretryable 409.
          await exportBatch([secondBatch], 'the second export');
        });

        await test.step('A third export with THREE top-level spans is accepted too', async () => {
          // The CompositeException case, and a separate claim from the one
          // above: the service dedups roots by trace id before creating, so
          // three parentless spans on one id are one create attempt — but
          // before the fix the per-root errors were collected into a
          // CompositeException that surfaced as a 500, which an exporter
          // retries indefinitely. A build that resumed only a SINGLE conflict
          // passes the step above and fails here.
          await exportBatch(thirdBatch, 'the third export');
        });

        const spanIds = await test.step('All five spans are stored, on that one trace', async () => {
          const spans = await spansIn(
            backendClient,
            project.id,
            allSpanNames.length,
            `the project '${project.name}'`,
          );
          expect(
            spans.map((s) => s.name).sort(),
            'every exported span is stored, from all three batches — a missing one is the ' +
              'dropped batch this spec exists for',
          ).toEqual([...allSpanNames].sort());
          // One trace_id across all five, and it is the trace the FIRST export
          // created. Asserted as a set so a build that stored every span while
          // minting a trace per batch fails here — which renders as a project
          // full of one-span traces rather than as an error, and would satisfy
          // the span count above on its own.
          expect(
            [...new Set(spans.map((s) => s.traceId))],
            'the later batches joined the first batch’s trace rather than minting their own',
          ).toEqual([traceId]);
          return new Map(spans.map((s) => [s.name, s.id]));
        });

        await test.step('…and the project still holds exactly that one trace', async () => {
          // The other half of the same failure. "Five spans share one trace id"
          // is true of a build that also left four orphan trace rows behind,
          // and the Logs table is where a user would see them.
          const traces = await backendClient.listTraceIds({ projectId: project.id });
          expect(
            traces,
            'three exports of one OTLP trace id are one Opik trace, not three',
          ).toEqual([traceId]);
        });

        await test.step('The trace panel counts all five spans and renders every node', async () => {
          // The UI half, and the reason this candidate is not API-only: the
          // user-visible failure is a span tree missing its later half, which
          // is a claim about the page someone opens to debug a request. A
          // backend that accepted the batch while re-parenting or losing a span
          // reads there as a broken tree rather than as an API error.
          expect(
            spanIds.size,
            'every span name resolved to an id before the browser opens',
          ).toBe(allSpanNames.length);

          const logs = new LogsPage(page);
          await logs.goto(project.id);
          const panel = await logs.openTraceById(traceId);
          await panel.waitForFullyLoaded();

          // Every name EXCEPT the first batch's, and the exclusion is a
          // property of OTLP ingestion rather than a weakened assertion:
          // `OpenTelemetryService` builds the trace from the first root span it
          // sees and copies that span's name onto it
          // (`traceBuilder.name(rootSpan.name())`). The tree testids the trace
          // node and the span nodes alike as `trace-tree-node-<name>`, so the
          // first batch's name legitimately resolves to TWO nodes — the trace
          // and its own span — and is asserted as such just below.
          //
          // The tree is filled by a rate-limited spans call whose shed answer is
          // cached, so this wait reloads rather than waiting longer; see the POM
          // method. Everything after it can assume the tree rendered.
          await panel.waitForSpanTreeNodes([secondBatch, ...thirdBatch]);
          await expect(
            panel.traceNameInHeader(firstBatch),
            'the trace took its name from the first export’s root span, which is why that name ' +
              'appears twice in the tree',
          ).toBeVisible();
          await expect(
            panel.spanTreeNode(firstBatch),
            'exactly two nodes for the first batch’s name — the trace node and the span’s own. ' +
              'One would mean the span that created the trace is itself missing from the tree',
          ).toHaveCount(2);
          await expect(
            panel.spansCountLabel(allSpanNames.length),
            'and the panel counts every span the three exports contributed — the number a user ' +
              'reads to decide whether a batch went missing',
          ).toBeVisible();
        });
      },
    );

    test(
      'an export naming a different project for a trace that already exists is still refused, and stores nothing',
      { tag: ['@cap:traces.otel-project-routing'] },
      async ({ backendClient, project, testNamespace, registerProjectCleanup }) => {
        test.setTimeout(180_000);

        // The control that keeps the test above from reading as "every repeat
        // export is now accepted". opik#9312's own comment draws the line here:
        // a root span conflicting with a trace in the SAME project is resumed,
        // one conflicting with a trace in ANOTHER project stays an
        // IdentifierMismatchException. A fix that resumed both would silently
        // merge two services' traces, and nothing in the first test can see it.
        const otherName = `${testNamespace}-other-project`;
        const sharedTraceId = otlpTraceId();
        // The precondition that makes the refusal a shape rather than a coin
        // toss — see the file header. Both exports derive their Opik trace id
        // from (OTLP id, millisecond), and only a collision reaches the
        // cross-project check at all.
        const startTimeMs = Date.now();
        const firstSpan = `${testNamespace}-owner-span`;
        const refusedSpan = `${testNamespace}-refused-span`;

        const other = await test.step('Create the second project up front', async () => {
          // Created by the TEST rather than left to ingestion's get-or-create.
          // The assertion below is that this project holds nothing, and a
          // project that does not exist holds nothing too — so creating it
          // first is what makes the emptiness a statement about the refused
          // export instead of a tautology.
          registerProjectCleanup(otherName);
          await backendClient.createProject(otherName);
          const matches = (await backendClient.listProjectsWithPrefix(otherName)).filter(
            (p) => p.name === otherName,
          );
          expect(matches, `exactly one project called '${otherName}'`).toHaveLength(1);
          return matches[0];
        });

        await test.step('One export creates the trace under the first project', async () => {
          const { status, message } = await backendClient.postOtelExport({
            projectNameHeader: project.name,
            spans: [{ name: firstSpan, otelTraceIdHex: sharedTraceId }],
            startTimeMs,
          });
          expect(status, `the owning export must be accepted: ${message}`).toBe(200);
        });

        const traceId = await test.step('…and that trace is readable before the second export', async () => {
          // Polled, not assumed: the refusal below depends on the trace
          // EXISTING, so an export sent before the first one's write had
          // settled would be accepted and this test would fail on a race
          // rather than on the product.
          await expect
            .poll(async () => (await backendClient.listTraceIds({ projectId: project.id })).length, {
              timeout: LISTING_SETTLE_MS,
              intervals: [500, 1_000, 2_000],
              message: 'the first export must have created its trace, or there is no conflict',
            })
            .toBe(1);
          const traces = await backendClient.listTraceIds({ projectId: project.id });
          expect(traces, 'one trace in the owning project').toHaveLength(1);
          return traces[0];
        });

        await test.step('The same OTLP trace id routed to the other project is refused', async () => {
          const { status, message } = await backendClient.postOtelExport({
            projectNameHeader: project.name,
            resourceAttributes: { 'opik.project_name': otherName },
            spans: [{ name: refusedSpan, otelTraceIdHex: sharedTraceId }],
            startTimeMs,
          });
          expect(
            status,
            'a trace that belongs to another project must stay a refusal — accepting it would ' +
              `merge two projects' traces: ${message}`,
          ).toBe(409);
          // The reason, not just the code: a 409 raised by some other conflict
          // — including the EntityAlreadyExists the first test proves is now
          // resumed — would satisfy the status alone and leave the distinction
          // this test exists for unasserted.
          expect(
            message,
            'and it must be the identifier-mismatch refusal specifically',
          ).toContain('do not match the existing trace');
        });

        await test.step('The other project stored nothing at all', async () => {
          await staysEmpty(backendClient, other.id, `the other project '${otherName}'`);
          expect(
            await backendClient.listTraceIds({ projectId: other.id }),
            'nor a trace — a refused export must not half-land',
          ).toHaveLength(0);
        });

        await test.step('…and the owning project is untouched', async () => {
          // The bystander. Without it, "the other project is empty" is equally
          // satisfied by a refusal that also rolled back or corrupted the
          // trace it was refused on behalf of.
          const spans = await spansIn(
            backendClient,
            project.id,
            1,
            `the owning project '${project.name}'`,
          );
          expect(
            spans.map((s) => s.name),
            'the owning project still holds exactly its own span',
          ).toEqual([firstSpan]);
          expect(spans[0].traceId, 'still on the trace it was written to').toBe(traceId);
          expect(
            await backendClient.listTraceIds({ projectId: project.id }),
            'and still exactly one trace',
          ).toEqual([traceId]);
        });
      },
    );
  },
);
