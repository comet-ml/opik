import { test, expect } from '@e2e/fixtures';
import { uuid7 } from '@e2e/core/backend';
import type { BackendClient, ProjectRef } from '@e2e/core/backend';

/**
 * `GET /v1/private/spans/{id}` for a span id written under more than one
 * project answers the LATEST write, not a 500 (opik#9311 / OPIK-9029).
 *
 * `SpanDAO.getByIds` resolves the projects holding an id first and then dedups
 * per `(workspace_id, project_id, id)`, so an id present in two projects comes
 * back as two rows. `getById` used to reduce that stream with a strict
 * single-item reducer, which throws `IndexOutOfBoundsException` ("Source
 * emitted more than one item") on the second row and surfaces as a hard 500 —
 * on a read every span detail view and every by-id consumer depends on. The fix
 * replaces the reducer with `LATEST_SPAN_ORDER`:
 * `comparing(lastUpdatedAt).thenComparing(projectId)`.
 *
 * A caller-minted span id reaching two projects is not exotic. An id is a
 * client-side UUIDv7, so any producer that replays a batch under a different
 * project name does it — a re-run of an instrumented job with
 * `OPIK_PROJECT_NAME` changed, an OTel Collector reconfigured mid-flight, a
 * copied notebook. Nothing refuses the second write, so the duplication is
 * invisible until something reads the id back.
 *
 * ## Why this is not already covered
 *
 * `GET /v1/private/spans/{id}` is well exercised — `getSpanPayload` is how four
 * specs read a span's stored fields, and `span-by-id-aged-render.spec.ts` drives
 * it at four id ages — but every one of them writes the id under exactly ONE
 * project. The single-row path is therefore guarded thoroughly and the fan-out
 * not at all, which is the asymmetry this release's defect lived in.
 * `span-read-mapping-and-parent-guard.spec.ts` is the nearest neighbour and
 * reads by TRACE, a different query that is project-scoped and cannot fan out.
 *
 * ## Why there is no page here
 *
 * The exploration candidate expected the trace panel to be the UI half, on the
 * grounds that it "resolves spans by id". It does not: `TraceDetailsPanel`
 * fills its tree and its detail pane from the by-TRACE listing
 * (`useSpansList`), and the only consumer of `useSpanById`/`useSpansByIds` in
 * the frontend is the Add-to-dataset dialog's advanced-mapping Preview
 * (`useMappingSampleEntities`, and only when the selection is spans). So a
 * trace-panel assertion here would be a test that cannot fail — it passes
 * against the broken build too, because the panel never issues the read that
 * 500s. Asserting it anyway would be worse than asserting nothing: it would
 * record a UI surface as guarded when the thing that breaks is upstream of it.
 * The mapping Preview is a genuine by-id consumer and a reasonable follow-up;
 * it is not driven here because it needs a dataset precondition and four dialog
 * steps to observe a value this spec already pins at its source.
 *
 * ## The two orderings, and why one is not enough
 *
 * Project ids are UUIDv7, so the project created second always sorts higher.
 * That makes "the latest write wins" and "the highest project id wins"
 * indistinguishable whenever the later write happens to be into the
 * later-created project — which is the natural case, and the one the first test
 * covers because it is also what a replay really looks like. The second test
 * inverts the write order against the creation order, so the later write is
 * into the LOWER-id project: a resolver ordering by project id returns the other
 * span and fails, while `LATEST_SPAN_ORDER` still returns the later write. Only
 * the pair can tell the documented rule from its most plausible impostor.
 */

/**
 * `java.util.UUID.compareTo`'s ordering, which is what `thenComparing(Span::projectId)`
 * applies — SIGNED 64-bit comparison of the most-significant half, then of the
 * least.
 *
 * Replicated rather than approximated with a string compare because the two
 * genuinely disagree: a UUID whose top bit is set sorts FIRST under Java's
 * signed comparison and LAST lexicographically. Today's UUIDv7s all begin with
 * a 48-bit millisecond whose top bit is clear, so the two orders happen to
 * agree — but the premise this spec pins is about the comparator the product
 * uses, and a helper that only happened to agree with it would quietly stop
 * pinning anything.
 */
function javaUuidOrder(uuid: string): [bigint, bigint] {
  const hex = uuid.replace(/-/g, '');
  expect(hex, `'${uuid}' is a 32-nibble UUID`).toHaveLength(32);
  const signed = (value: bigint): bigint => (value >= 1n << 63n ? value - (1n << 64n) : value);
  return [signed(BigInt(`0x${hex.slice(0, 16)}`)), signed(BigInt(`0x${hex.slice(16)}`))];
}

/** True when `left` sorts strictly above `right` under {@link javaUuidOrder}. */
function sortsHigher(left: string, right: string): boolean {
  const [leftHigh, leftLow] = javaUuidOrder(left);
  const [rightHigh, rightLow] = javaUuidOrder(right);
  return leftHigh === rightHigh ? leftLow > rightLow : leftHigh > rightHigh;
}

/**
 * A project created by the test, registered for teardown before it exists.
 *
 * Registered by NAME first and only then created, which is the order
 * `registerProjectCleanup` is built for: the id is not knowable until the
 * create has returned, so a registry filled afterwards would miss exactly the
 * project a failing create left half-made. Deleting a project takes its traces
 * and spans with it, which is every entity these tests write.
 */
async function freshProject(
  backendClient: BackendClient,
  registerProjectCleanup: (name: string) => void,
  name: string,
): Promise<ProjectRef> {
  registerProjectCleanup(name);
  await backendClient.createProject(name);
  const matches = (await backendClient.listProjectsWithPrefix(name)).filter(
    (p) => p.name === name,
  );
  // Exactly one, not `.at(0)`: the names in one test share a stem, so a
  // `startsWith` lookup resolving to a sibling would have the test asserting
  // against the wrong project for the rest of its run.
  expect(matches, `exactly one project called '${name}'`).toHaveLength(1);
  return matches[0];
}

/**
 * Write one span under `spanId` into `project`, on its own trace there, and
 * wait until that project's copy is readable.
 *
 * The wait is what makes the WRITE ORDER the test imposes real. `getById`
 * resolves by `last_updated_at`, so two writes that had not yet settled in the
 * order they were issued would make "the later write wins" a statement about a
 * race. Polled through the project-scoped by-trace read, which cannot fan out,
 * so this wait can never be satisfied by the other project's copy.
 */
async function writeSpanInto(
  backendClient: BackendClient,
  project: ProjectRef,
  spanId: string,
  spanName: string,
  traceName: string,
): Promise<{ traceId: string; spanName: string }> {
  const traceId = uuid7();
  await backendClient.createTracesBatch({
    projectName: project.name,
    traces: [{ id: traceId, name: traceName, input: {}, output: {} }],
  });
  await backendClient.createSpansBatch({
    projectName: project.name,
    spans: [
      {
        id: spanId,
        traceId,
        name: spanName,
        source: 'sdk',
        // Distinct per copy, so the by-id answer identifies WHICH write it
        // returned from its payload and not only from its project id — a
        // resolver that returned the right project's row with another's
        // content would otherwise pass.
        input: { wrote: spanName },
      },
    ],
  });
  await expect
    .poll(
      async () =>
        (await backendClient.listSpansPage({ projectId: project.id, traceId })).spans.length,
      {
        timeout: 120_000,
        intervals: [500, 1_000, 2_000],
        message: `the '${spanName}' copy must be readable in '${project.name}' before the next write`,
      },
    )
    .toBe(1);
  return { traceId, spanName };
}

/**
 * The by-id read, once it reports the fan-out at all.
 *
 * Polled because the two writes are separate ingests: a read issued before the
 * second one is queryable answers the FIRST write quite correctly, which is
 * indistinguishable from the resolver preferring the wrong row. The poll waits
 * for the expected project and then the assertions re-read and compare the
 * whole answer — so a build that never converged still fails here, naming the
 * project it kept returning.
 */
async function readUntilProject(
  backendClient: BackendClient,
  spanId: string,
  expectedProjectId: string,
  what: string,
): Promise<{ status: number; message: string; projectId: string | null; name: string }> {
  await expect
    .poll(
      async () => {
        const answer = await backendClient.readSpanById(spanId);
        return `${answer.status}:${answer.span?.projectId ?? 'none'}`;
      },
      {
        timeout: 120_000,
        intervals: [500, 1_000, 2_000],
        message:
          `${what} — a 500 here is the fan-out defect itself; a 200 naming the other project ` +
          'means the resolver ordered by project id rather than by write time',
      },
    )
    .toBe(`200:${expectedProjectId}`);

  const answer = await backendClient.readSpanById(spanId);
  expect(
    answer.status,
    `${what}: the read must answer 200, never the 500 a two-row stream used to raise: ${answer.message}`,
  ).toBe(200);
  expect(answer.span, 'a 200 carries the span payload').not.toBeNull();
  return {
    status: answer.status,
    message: answer.message,
    projectId: answer.span!.projectId,
    name: answer.span!.name,
  };
}

test.describe(
  'Span read — one span id written under two projects',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'the by-id read answers 200 with the later write, not a 500',
      { tag: ['@cap:traces.span-by-id-fan-out'] },
      async ({ project, backendClient, testNamespace, registerProjectCleanup }) => {
        // Two seeded projects, two trace writes, two span writes and two
        // settle polls, each of which may wait out a listing rate limit on
        // staging — well past the 90s default.
        test.setTimeout(300_000);

        // The shared id, minted once. A UUIDv7 because the backend refuses
        // anything else outright ("Span id must be a version 7 UUID"), which is
        // also why this case is reachable at all: the id comes from the client,
        // so nothing server-side gets to notice it is a re-use.
        const spanId = uuid7();
        const otherName = `${testNamespace}-earlier-project`;

        const other = await test.step('Create the other project the id will also live in', async () =>
          freshProject(backendClient, registerProjectCleanup, otherName));

        const earlier = await test.step('Write the span into the other project FIRST', async () =>
          writeSpanInto(
            backendClient,
            other,
            spanId,
            `${testNamespace}-earlier-copy`,
            `${testNamespace}-earlier-trace`,
          ));

        const later = await test.step('…then write the SAME id into the project under test', async () =>
          writeSpanInto(
            backendClient,
            project,
            spanId,
            `${testNamespace}-later-copy`,
            `${testNamespace}-later-trace`,
          ));

        await test.step('Both projects really hold a copy (the premise)', async () => {
          // Without this the read below could be answering a single-row stream
          // — the path that was never broken — and the test would pass having
          // never posed the question. Each read is project-scoped and by trace,
          // so neither can be satisfied by the other project's copy.
          const earlierRows = await backendClient.listSpansPage({
            projectId: other.id,
            traceId: earlier.traceId,
          });
          expect(
            earlierRows.spans.map((s) => s.id),
            `'${otherName}' holds its own copy of ${spanId}`,
          ).toEqual([spanId]);
          const laterRows = await backendClient.listSpansPage({
            projectId: project.id,
            traceId: later.traceId,
          });
          expect(
            laterRows.spans.map((s) => s.id),
            `'${project.name}' holds its own copy of the same id`,
          ).toEqual([spanId]);
        });

        await test.step('The by-id read answers 200 and names the later write', async () => {
          const answer = await readUntilProject(
            backendClient,
            spanId,
            project.id,
            'the by-id read of a span id present in two projects',
          );
          expect(
            answer.projectId,
            'the latest write wins, so the read resolves to the project written second',
          ).toBe(project.id);
          // The payload too, not only the project id: a resolver that picked
          // the right row's project while merging another row's fields would
          // satisfy the id alone, and `name` is the field a reader of a span
          // detail view would notice first.
          expect(
            answer.name,
            'and carries that write’s own content, not the earlier copy’s',
          ).toBe(later.spanName);
        });
      },
    );

    test(
      'the later write still wins when it is the LOWER-id project, so the project id is only a tie-break',
      { tag: ['@cap:traces.span-by-id-fan-out'] },
      async ({ backendClient, testNamespace, registerProjectCleanup }) => {
        test.setTimeout(300_000);

        // The discriminator. The test above cannot tell `comparing(lastUpdatedAt)`
        // from `comparing(projectId)`, because there the later write is also the
        // later-created (and so higher-id) project. Here the two disagree.
        const spanId = uuid7();
        const lowerName = `${testNamespace}-lower-id-project`;
        const higherName = `${testNamespace}-higher-id-project`;

        const [lower, higher] = await test.step('Create two projects, in id order', async () => {
          // Created in this order so the second one's UUIDv7 sorts above the
          // first's. The names say which is which, and the step below proves
          // the names are right rather than trusting the generator.
          const first = await freshProject(backendClient, registerProjectCleanup, lowerName);
          const second = await freshProject(backendClient, registerProjectCleanup, higherName);
          return [first, second];
        });

        await test.step('The second project really does hold the higher id (the premise)', async () => {
          // The whole test rests on this. If the ids came out the other way
          // round, the write order below would no longer invert anything and
          // this would silently become a duplicate of the first test — passing,
          // and no longer discriminating. Asserted under the product's own
          // comparator; see `javaUuidOrder`.
          expect(
            sortsHigher(higher.id, lower.id),
            `'${higherName}' (${higher.id}) must sort above '${lowerName}' (${lower.id}) under ` +
              "java.util.UUID.compareTo, or this test's write order inverts nothing",
          ).toBe(true);
        });

        const earlier = await test.step('Write the span into the HIGHER-id project first', async () =>
          writeSpanInto(
            backendClient,
            higher,
            spanId,
            `${testNamespace}-higher-copy`,
            `${testNamespace}-higher-trace`,
          ));

        const later = await test.step('…then write the same id into the LOWER-id project', async () =>
          writeSpanInto(
            backendClient,
            lower,
            spanId,
            `${testNamespace}-lower-copy`,
            `${testNamespace}-lower-trace`,
          ));

        await test.step('Both copies exist (the premise)', async () => {
          for (const [projectRef, seeded] of [
            [higher, earlier],
            [lower, later],
          ] as const) {
            const rows = await backendClient.listSpansPage({
              projectId: projectRef.id,
              traceId: seeded.traceId,
            });
            expect(
              rows.spans.map((s) => s.id),
              `'${projectRef.name}' holds its own copy of ${spanId}`,
            ).toEqual([spanId]);
          }
        });

        await test.step('The read resolves to the LOWER-id project, because it was written last', async () => {
          const answer = await readUntilProject(
            backendClient,
            spanId,
            lower.id,
            'the by-id read with the write order inverted against the project-id order',
          );
          expect(
            answer.projectId,
            `the read must resolve to '${lowerName}' — a resolver ordering by project id would ` +
              `return '${higherName}' here, and both orderings agree in the test above`,
          ).toBe(lower.id);
          expect(answer.name, 'and carry the last write’s own content').toBe(later.spanName);
          expect(
            answer.name,
            'never the first write’s, which is the answer a project-id ordering would give',
          ).not.toBe(earlier.spanName);
        });
      },
    );
  },
);
