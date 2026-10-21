import { test, expect } from '@e2e/fixtures';
import { LogsPage } from '@e2e/pom/logs.page';
import type { BackendClient, OtelExportSpanSeed } from '@e2e/core/backend';
import { randomBytes } from 'node:crypto';

/**
 * OTLP ingestion routing by the `opik.project_name` attribute (OPIK-8888 /
 * opik#9036), with the `projectName` header demoted to a fallback.
 *
 * Before this PR the header decided the project for a whole export. Now each
 * span can name its own, as a resource attribute or a span attribute, and the
 * project is resolved get-or-create — so an export can bring a project into
 * existence. The header only applies to spans that named nothing, or whose name
 * was refused.
 *
 * This is the only path an OTel Collector user has, and the failure mode is not
 * an error: the span is accepted, stored, and in a project nobody is watching.
 * A user stares at an empty project while their production traces accumulate
 * somewhere else, and the data is already written by the time anyone notices.
 *
 * ## Why nothing here was covered
 *
 * `otel-provider-alias-cost.spec.ts` is the estate's only other caller of this
 * endpoint, and it asserts the HEADER path — `postOtelSpans` sends
 * `{ projectName }` and attaches every span to a caller-minted trace via
 * `opik.trace_id`. That is the pre-PR behaviour, and it is deliberately the one
 * thing an assertion about attribute routing must not do: an `opik.trace_id`
 * pins the spans to a trace that already belongs to a project, which is then the
 * project they land in whatever `opik.project_name` says. Hence
 * `postOtelExport`, which sets no `opik.trace_id` at all and lets the service
 * mint the trace in whichever project the attribute resolved to.
 *
 * ## Determinism: one destination per OTLP trace id
 *
 * An Opik trace id is derived from the OTLP trace id, so spans of one export
 * that fan out to several projects collide on the per-project trace upsert: the
 * loser is rejected with `409 Project name and workspace name do not match the
 * existing trace` and its span is never written. Exploration measured that on
 * staging at 2.2.111 — 7 of 8 four-project exports failed, losing 14 of 32
 * spans — and it is a RACE, not a shape, so it is not assertable either way
 * without a repeat-until-flaky loop. Every multi-project export below therefore
 * gives each span its OWN OTLP trace id, which exploration found passes every
 * time; every single-destination export shares one, which is what a real
 * Collector batch looks like. That known defect is reported separately rather
 * than pinned here: a spec that asserts a race is a spec that fails at random.
 *
 * ## The header project is the bystander throughout
 *
 * Every test asserts what the HEADER-named project holds as well as the routed
 * one. "The routed project has the span" would be satisfied just as well by an
 * ingestion that wrote the span to both, and duplicated traces across projects
 * is the other half of the same defect — so the fallback project is read back
 * by id in every case, and asserted empty exactly when it should be.
 */

/** A random OTLP trace id as lowercase hex, for a span that must route alone. */
const ownTraceId = (): string => randomBytes(16).toString('hex');

/**
 * How long the Spans table gets to report its population, matching
 * `spans-view-pagination.spec.ts`'s own constant and for its reason: the spans
 * listing shares a per-workspace rate limit, and a squeezed fetch renders an
 * empty table with no error a locator could see. `TracesSpansTab` refetches
 * every 30s, so a budget spanning two of those intervals turns an
 * infrastructure blip into a delay rather than a failure, while a real routing
 * defect still fails — just later. Measured: a 30s budget here went flaky
 * under four parallel workers, reading a `null` footer for the whole window.
 */
const TABLE_SETTLE_MS = 90_000;

/**
 * `name` padded with ASCII to exactly `codePoints` code points.
 *
 * ASCII only, so one code point is one char and `length` IS the code-point
 * count — which is what `OpenTelemetryMapper.isValidProjectName` measures
 * (`stripped.codePointCount(0, length) <= 150`). Asserted rather than
 * truncated: a stem that had outgrown the target would silently produce a name
 * of the wrong length, and the boundary this pins would move with it.
 */
function nameOfExactly(stem: string, codePoints: number): string {
  expect(
    stem.length,
    `the stem must fit inside ${codePoints} code points to pin that boundary`,
  ).toBeLessThan(codePoints);
  return stem + 'x'.repeat(codePoints - stem.length);
}

/**
 * The project called `name`, once it exists, or `null`.
 *
 * Exact-match over the prefix search, and asserted unique: the names in one
 * test share a stem (the 150- and 151-code-point pair differ only in length),
 * so a `startsWith` lookup would resolve to whichever the server ordered first
 * and the test would assert against the wrong project.
 */
async function projectNamed(
  backendClient: BackendClient,
  name: string,
): Promise<{ id: string; name: string } | null> {
  const matches = (await backendClient.listProjectsWithPrefix(name)).filter(
    (p) => p.name === name,
  );
  expect(
    matches.length,
    `at most one project may be called '${name}'`,
  ).toBeLessThanOrEqual(1);
  return matches[0] ?? null;
}

/** Wait until a project called `name` exists, and return it. */
async function waitForProject(
  backendClient: BackendClient,
  name: string,
): Promise<{ id: string; name: string }> {
  await expect
    .poll(async () => (await projectNamed(backendClient, name)) !== null, {
      timeout: 60_000,
      intervals: [500, 1_000, 2_000],
      message: `ingestion must get-or-create the project named by opik.project_name ('${name}')`,
    })
    .toBe(true);
  const project = await projectNamed(backendClient, name);
  expect(project, `project '${name}' resolved after the poll saw it`).not.toBeNull();
  return project!;
}

/**
 * The span names a project holds, once it holds exactly `expected` of them.
 *
 * Polls on the envelope's `total` and then re-reads, so the returned rows and
 * the asserted population come from the same answer. The count is the
 * assertion, not a lookup for the expected name: a read that ALSO returned a
 * span the export should have routed elsewhere would still contain the one
 * being looked for, and a leak is the failure here.
 */
async function spanNamesIn(
  backendClient: BackendClient,
  projectId: string,
  expected: number,
  what: string,
): Promise<string[]> {
  await expect
    .poll(async () => (await backendClient.listProjectSpans({ projectId })).total, {
      timeout: 60_000,
      intervals: [500, 1_000, 2_000],
      message: `${what} must hold exactly ${expected} span(s)`,
    })
    .toBe(expected);
  const page = await backendClient.listProjectSpans({ projectId });
  expect(page.total, `${what}: the envelope total`).toBe(expected);
  expect(page.spans, `${what}: the rows behind that total`).toHaveLength(expected);
  return page.spans.map((s) => s.name).sort();
}

/**
 * A project holds nothing — asserted as a settled fact, not a first read.
 *
 * `toBe(0)` on a single read of a project that is ABOUT to receive a span
 * passes before the write lands, so the bystander assertion would be green for
 * the wrong reason. `toPass` over a window re-reads until the deadline, which
 * means the span had time to arrive and did not.
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
  'OTel ingestion — opik.project_name routing',
  { tag: ['@t2-cuj', '@area:traces'] },
  () => {
    test(
      'A resource-level opik.project_name auto-creates its project and holds the export, leaving the header project empty',
      { tag: ['@cap:traces.otel-project-routing'] },
      async ({ backendClient, project, testNamespace, registerProjectCleanup }) => {
        const routedName = `${testNamespace}-routed`;
        const spanName = `${testNamespace}-resource-routed-span`;
        registerProjectCleanup(routedName);

        await test.step('The routed project does NOT exist before the export', async () => {
          // The whole claim is that INGESTION created it. Without this, a name
          // that happened to exist already would make the assertions below pass
          // over a project the export merely wrote into.
          expect(
            await projectNamed(backendClient, routedName),
            `'${routedName}' must not exist yet, or auto-creation is not what is being asserted`,
          ).toBeNull();
        });

        await test.step('Export one span naming a project that does not exist, under a different header', async () => {
          const { status, message } = await backendClient.postOtelExport({
            projectNameHeader: project.name,
            resourceAttributes: { 'opik.project_name': routedName },
            spans: [{ name: spanName }],
          });
          expect(status, `the export must be accepted: ${message}`).toBe(200);
        });

        const routed = await test.step('Ingestion get-or-created the named project', async () =>
          waitForProject(backendClient, routedName));

        await test.step('It holds exactly the exported span, on exactly one trace', async () => {
          expect(
            await spanNamesIn(backendClient, routed.id, 1, `the routed project '${routedName}'`),
          ).toEqual([spanName]);
          // One trace, not just one span: the service mints a trace per OTLP
          // trace id, and a routing change that minted one per span would
          // fragment a Collector batch into unrelated traces while every span
          // count above stayed correct.
          const traces = await backendClient.listTraceIds({ projectId: routed.id });
          expect(traces, 'the export is one trace in the routed project').toHaveLength(1);
        });

        await test.step('The header-named project received nothing', async () => {
          await staysEmpty(backendClient, project.id, `the header project '${project.name}'`);
          expect(
            await backendClient.listTraceIds({ projectId: project.id }),
            'the header project holds no trace either — the attribute replaced it, it did not duplicate into it',
          ).toHaveLength(0);
        });
      },
    );

    test(
      'The routed span does not carry opik.project_name in its stored metadata',
      { tag: ['@cap:traces.otel-project-routing'] },
      async ({ backendClient, project, testNamespace, registerProjectCleanup }) => {
        // `opik.metadata.`-prefixed, so GeneralMappingRules maps it to METADATA
        // by an explicit rule rather than by the default. This marker is what
        // makes the absence below mean something: "metadata has no
        // opik.project_name" is also true of a span whose metadata is empty or
        // null, which would be a different regression passing as this one.
        const routedName = `${testNamespace}-stripped`;
        const spanName = `${testNamespace}-stripped-span`;
        const markerValue = `${testNamespace}-marker`;
        registerProjectCleanup(routedName);

        await test.step('Export one routed span carrying a metadata marker beside the routing attribute', async () => {
          const { status, message } = await backendClient.postOtelExport({
            projectNameHeader: project.name,
            spans: [
              {
                name: spanName,
                attributes: {
                  'opik.project_name': routedName,
                  'opik.metadata.cuj_marker': markerValue,
                },
              },
            ],
          });
          expect(status, `the export must be accepted: ${message}`).toBe(200);
        });

        const routed = await waitForProject(backendClient, routedName);
        await spanNamesIn(backendClient, routed.id, 1, `the routed project '${routedName}'`);

        await test.step('The stored metadata kept the marker and dropped the routing attribute', async () => {
          const spans = (await backendClient.listProjectSpans({ projectId: routed.id })).spans;
          expect(spans, 'the routed span is readable by id').toHaveLength(1);
          const payload = await backendClient.getSpanPayload(spans[0].id);
          expect(payload, `span ${spans[0].id} has a readable payload`).not.toBeNull();

          const metadata = JSON.stringify(payload!.metadata ?? null);
          expect(
            metadata,
            'the mapper really wrote metadata for this span — otherwise the absence below says nothing',
          ).toContain(markerValue);
          expect(
            metadata,
            'opik.project_name is consumed for routing and must not be stored as a span attribute',
          ).not.toContain('opik.project_name');
          expect(
            metadata,
            'nor may the resolved project name survive under another key',
          ).not.toContain(routedName);
        });
      },
    );

    test(
      'Per-span opik.project_name routes each span of one export to its own project',
      { tag: ['@cap:traces.otel-project-routing'] },
      async ({ backendClient, project, testNamespace, registerProjectCleanup }) => {
        const firstName = `${testNamespace}-split-a`;
        const secondName = `${testNamespace}-split-b`;
        const firstSpan = `${testNamespace}-split-a-span`;
        const secondSpan = `${testNamespace}-split-b-span`;
        registerProjectCleanup(firstName);
        registerProjectCleanup(secondName);

        await test.step('Export two spans naming two projects, each under its own OTLP trace id', async () => {
          // Own trace ids deliberately — see the file header. With one shared
          // id this export is the 409 race, and the spec would fail at random
          // on a defect it is not about.
          const { status, message } = await backendClient.postOtelExport({
            projectNameHeader: project.name,
            spans: [
              {
                name: firstSpan,
                attributes: { 'opik.project_name': firstName },
                otelTraceIdHex: ownTraceId(),
              },
              {
                name: secondSpan,
                attributes: { 'opik.project_name': secondName },
                otelTraceIdHex: ownTraceId(),
              },
            ],
          });
          expect(status, `the export must be accepted: ${message}`).toBe(200);
        });

        const [first, second] = await test.step('Both named projects were created', async () => [
          await waitForProject(backendClient, firstName),
          await waitForProject(backendClient, secondName),
        ]);

        await test.step('Each project holds exactly its own span and not the other', async () => {
          // Exact collections, so a span written to BOTH projects fails here.
          // That is the failure a per-project lookup would miss, and the one
          // the shared-trace-id collision produces when it does not 409.
          expect(await spanNamesIn(backendClient, first.id, 1, `project '${firstName}'`)).toEqual([
            firstSpan,
          ]);
          expect(await spanNamesIn(backendClient, second.id, 1, `project '${secondName}'`)).toEqual(
            [secondSpan],
          );
        });

        await test.step('The header project took neither', async () => {
          await staysEmpty(backendClient, project.id, `the header project '${project.name}'`);
        });
      },
    );

    test(
      'A project name of 150 code points routes, 151 is refused and falls back to the header',
      { tag: ['@cap:traces.otel-project-routing'] },
      async ({ backendClient, project, testNamespace, registerProjectCleanup }) => {
        // The boundary is an exact integer in the product
        // (`MAX_PROJECT_NAME_LENGTH = 150`, compared with `<=`), not a
        // threshold that drifts — so both sides of it are assertable, and
        // asserting both is what distinguishes "the limit moved by one" from
        // "the limit is gone".
        const atLimit = nameOfExactly(`${testNamespace}-at150-`, 150);
        const overLimit = nameOfExactly(`${testNamespace}-over151-`, 151);
        const wayOver = nameOfExactly(`${testNamespace}-over224-`, 224);
        const atLimitSpan = `${testNamespace}-at-limit-span`;
        const overLimitSpan = `${testNamespace}-over-limit-span`;
        const wayOverSpan = `${testNamespace}-way-over-span`;
        // Registered even for the names that must NOT be created: a product
        // that created them anyway is the failure, and the leak would then
        // corrupt the next run's prefix counts rather than just failing here.
        for (const name of [atLimit, overLimit, wayOver]) registerProjectCleanup(name);

        await test.step('Three exports, one destination each, at 150, 151 and 224 code points', async () => {
          // One export per name, each with a single span, so each stays a
          // single-destination export and no trace-id collision is possible.
          for (const [name, spanName] of [
            [atLimit, atLimitSpan],
            [overLimit, overLimitSpan],
            [wayOver, wayOverSpan],
          ] as const) {
            const { status, message } = await backendClient.postOtelExport({
              projectNameHeader: project.name,
              spans: [{ name: spanName, attributes: { 'opik.project_name': name } }],
            });
            expect(
              status,
              `the export naming a ${name.length}-code-point project must still be accepted: ${message}`,
            ).toBe(200);
          }
        });

        await test.step('The 150-code-point name routed to its own project', async () => {
          const routed = await waitForProject(backendClient, atLimit);
          expect(
            await spanNamesIn(backendClient, routed.id, 1, 'the 150-code-point project'),
          ).toEqual([atLimitSpan]);
        });

        await test.step('Both over-limit names fell back to the header project, and neither was created', async () => {
          // The fallback is asserted FIRST as a collection of exactly the two
          // over-limit spans. That is the positive half: a name that was simply
          // dropped on the floor would leave no project AND no span, which a
          // project-absence check alone would read as a pass.
          expect(
            await spanNamesIn(
              backendClient,
              project.id,
              2,
              `the header project '${project.name}'`,
            ),
          ).toEqual([overLimitSpan, wayOverSpan].sort());

          for (const name of [overLimit, wayOver]) {
            expect(
              await projectNamed(backendClient, name),
              `a ${name.length}-code-point name is over the 150 limit and must create no project`,
            ).toBeNull();
          }
        });
      },
    );

    test(
      'A span with no opik.project_name follows the projectName header',
      { tag: ['@cap:traces.otel-project-routing'] },
      async ({ backendClient, project, testNamespace }) => {
        const spanName = `${testNamespace}-header-only-span`;

        await test.step('Export one span carrying no routing attribute at all', async () => {
          const { status, message } = await backendClient.postOtelExport({
            projectNameHeader: project.name,
            spans: [{ name: spanName }],
          });
          expect(status, `the export must be accepted: ${message}`).toBe(200);
        });

        await test.step('The header project holds exactly it', async () => {
          // The pre-PR path, asserted because it is now a FALLBACK rather than
          // the rule: the header is consulted only when the attribute is
          // missing or refused, so a routing change that stopped reading the
          // header at all would strand every uninstrumented export.
          expect(
            await spanNamesIn(backendClient, project.id, 1, `the header project '${project.name}'`),
          ).toEqual([spanName]);
        });
      },
    );

    test(
      'Two Unicode spellings of one project name collapse to a single project',
      { tag: ['@cap:traces.otel-project-routing'] },
      async ({ backendClient, project, testNamespace, registerProjectCleanup }) => {
        // The same name written two ways: precomposed U+00E9, and 'e' followed
        // by the combining acute U+0301. Different byte strings, different code
        // point counts, one name as far as a human is concerned — so an
        // ingestion that treated them as two would silently split one service's
        // traces across two projects, each looking half-empty.
        const stem = `${testNamespace}-cafe-`;
        const composed = `${stem}Café`;
        const decomposed = `${stem}Café`;
        const composedSpan = `${testNamespace}-nfc-span`;
        const decomposedSpan = `${testNamespace}-nfd-span`;
        registerProjectCleanup(composed);
        registerProjectCleanup(decomposed);

        await test.step('Export both spellings in ONE export, sharing one OTLP trace id', async () => {
          // Shared id is correct and deliberate here: the point is that both
          // spans resolve to ONE project, so there is no fan-out to collide.
          // If the collation broke, this export would also be the 409 race —
          // which is itself a signal, and the span counts below say which.
          const spans: OtelExportSpanSeed[] = [
            { name: composedSpan, attributes: { 'opik.project_name': composed } },
            { name: decomposedSpan, attributes: { 'opik.project_name': decomposed } },
          ];
          const { status, message } = await backendClient.postOtelExport({
            projectNameHeader: project.name,
            spans,
          });
          expect(status, `the export must be accepted: ${message}`).toBe(200);
        });

        const routed = await test.step('Exactly ONE project exists for the pair', async () => {
          await expect
            .poll(async () => (await backendClient.listProjectsWithPrefix(stem)).length, {
              timeout: 60_000,
              intervals: [500, 1_000, 2_000],
              message: 'the two spellings must resolve to one project, not two',
            })
            .toBe(1);
          const matches = await backendClient.listProjectsWithPrefix(stem);
          expect(matches, 'one project for both spellings').toHaveLength(1);
          return matches[0];
        });

        await test.step('It holds one trace carrying both spans', async () => {
          expect(
            await spanNamesIn(backendClient, routed.id, 2, `the collated project '${routed.name}'`),
          ).toEqual([composedSpan, decomposedSpan].sort());
          const traces = await backendClient.listTraceIds({ projectId: routed.id });
          expect(
            traces,
            'one OTLP trace id resolving to one project is one Opik trace',
          ).toHaveLength(1);
        });

        await test.step('The header project took neither spelling', async () => {
          await staysEmpty(backendClient, project.id, `the header project '${project.name}'`);
        });
      },
    );

    test(
      'The auto-created project renders exactly the routed spans in its Logs view',
      { tag: ['@cap:traces.otel-project-routing'] },
      async ({ backendClient, project, testNamespace, registerProjectCleanup, page }) => {
        // The 90s table budget below can outlast the 90s default test timeout,
        // so this one needs the longer grant — same pairing as
        // `spans-view-pagination.spec.ts`.
        test.slow();
        // The UI half, and the reason this candidate is not API-only: "which
        // project holds the trace" is a claim about a page a user opens, and
        // the user-visible failure is an empty project. A span routed correctly
        // into a project the Logs view cannot show is the same experience as a
        // span that never arrived.
        const routedName = `${testNamespace}-ui-routed`;
        const spanNames = [0, 1, 2].map((i) => `${testNamespace}-ui-span-${i}`);
        registerProjectCleanup(routedName);

        await test.step('Export three spans to one new project', async () => {
          // One destination, so one shared OTLP trace id is safe and is what a
          // real Collector batch looks like.
          const { status, message } = await backendClient.postOtelExport({
            projectNameHeader: project.name,
            resourceAttributes: { 'opik.project_name': routedName },
            spans: spanNames.map((name) => ({ name })),
          });
          expect(status, `the export must be accepted: ${message}`).toBe(200);
        });

        const routed = await waitForProject(backendClient, routedName);

        const apiSpanIds = await test.step('The API reports all three in the routed project', async () => {
          // Asserted before the browser opens: a UI assertion over an export
          // that never landed is a test that cannot fail — the rows would
          // simply never appear, indistinguishable from a rendering bug.
          expect(
            await spanNamesIn(backendClient, routed.id, 3, `the routed project '${routedName}'`),
          ).toEqual([...spanNames].sort());
          const spans = (await backendClient.listProjectSpans({ projectId: routed.id })).spans;
          return spans.map((s) => s.id).sort();
        });

        await test.step('The Logs Spans view agrees with it, total and rows', async () => {
          const logs = new LogsPage(page);
          await logs.gotoSpans(routed.id, { size: 25 });
          // No `waitForReady()`: it waits on the TRACES tab's "Traces <n>"
          // metrics text and its "No traces yet" empty state, neither of which
          // the Spans view renders. The pagination poll below is the readiness
          // signal the estate's other spans-view specs use
          // (`spans-view-pagination.spec.ts`), and it is also the assertion.

          // The footer total comes from the listing envelope, so it is where a
          // read that lost rows shows up; the row ids are what the user can
          // actually see. Both, because they fail apart.
          await expect
            .poll(() => logs.readPaginationTotal(), {
              timeout: TABLE_SETTLE_MS,
              intervals: [500, 1_000, 2_000],
              message: 'the Spans view footer must report the routed population',
            })
            .toBe(3);
          expect(
            (await logs.readRowIdsOnPage()).sort(),
            'the rendered rows are exactly the spans the API routed here',
          ).toEqual(apiSpanIds);
        });
      },
    );
  },
);
