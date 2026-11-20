import { test, expect } from '@e2e/fixtures';

/**
 * A dashboard name is not a unique key, so two creates of one name never
 * collide — however they are timed (opik#9189, OPIK-8173).
 *
 * `insights-views` and `dashboards` used to derive a workspace-wide unique
 * `slug` from the name. The derivation was a read-then-insert with no lock, so
 * two creates of the same name landing together both computed the same slug and
 * the loser came back `409 "Dashboard already exists"` — on a name no dashboard
 * held. 9189 removes the slug and the unique key with it (`SlugUtils` is gone
 * from the backend in this release), which makes the name free by construction
 * rather than free until two requests overlap.
 *
 * ## Why this is API-level, and why concurrency is the whole point
 *
 * Nothing about the fix is visible in a page. The defect was a race between two
 * writes, and a browser can only issue them one at a time through the create
 * dialog — which is precisely how it stayed hidden. Driving the endpoint
 * directly is the only way to put two creates in flight at once, and
 * `Promise.all` over `fetch` does it with no UI in the way.
 *
 * ## The spec this exists beside, and why it is not enough
 *
 * `project-overview-template-copy.spec.ts` meets this defect from the other
 * side: three of its tests create the app-generated name "Project overview
 * (custom)", so under the repo's `fullyParallel: true` they raced each other and
 * failed about two runs in five. Its fix was
 * `test.describe.configure({ mode: 'default' })` — never overlap — and its file
 * comment says so. That makes it GREEN against a build with the slug restored,
 * because it no longer produces the conditions. So the estate would pass a
 * revert of 9189, which is the gap this fills: the serialized spec asserts the
 * copy flow, and this one asserts that the flow did not need serializing.
 *
 * Deliberately NOT asserted here: whether two dashboards of one name inside one
 * scope SHOULD be accepted. They are, as of 9189 — the slug was the only
 * uniqueness constraint anywhere, so dropping it allows same-scope duplicates
 * too. Whether that is the intended trade-off is a product decision for
 * OPIK-8173's owner and not something a test should freeze in either direction.
 * What this spec pins is only the part 9189 set out to fix: a create that used
 * to fail on a name nobody held now succeeds.
 */

/** The 409 message `DashboardServiceImpl` raises, which must appear nowhere. */
const ALREADY_EXISTS = 'Dashboard already exists';

/**
 * How many times to repeat the concurrent insights-view pair.
 *
 * The defect was a race, so one round that happens to serialize proves nothing;
 * repeating narrows the window where a restored constraint goes unnoticed. Three
 * is what the exploration drove — six creates — and is enough to have caught the
 * pre-9189 behaviour, which lost roughly two rounds in five.
 */
const CONCURRENT_ROUNDS = 3;

test.describe(
  'Dashboards — a name is not a unique key',
  { tag: ['@t2-cuj', '@area:dashboards'] },
  () => {
    test(
      'two dashboards created concurrently under one name both succeed, in one project and at workspace level',
      { tag: ['@cap:dashboards.create-dashboard'] },
      async ({ project, secondProject, backendClient, registerDashboardCleanup, testNamespace }) => {
        const sharedName = `${testNamespace}-dash`;

        const acrossProjects = await test.step(
          'Two concurrent creates of one name, one in each project',
          async () =>
            // In flight together, which is the condition under test: issued one
            // after the other, both would pass against the pre-9189 backend too.
            Promise.all([
              backendClient.createDashboardRaw({ name: sharedName, projectId: project.id }),
              backendClient.createDashboardRaw({ name: sharedName, projectId: secondProject.id }),
            ]),
        );

        const atWorkspaceLevel = await test.step(
          'Two more, with no project at all',
          async () =>
            Promise.all([
              backendClient.createDashboardRaw({ name: `${sharedName}-ws` }),
              backendClient.createDashboardRaw({ name: `${sharedName}-ws` }),
            ]),
        );

        const all = [...acrossProjects, ...atWorkspaceLevel];
        // Registered before a single assertion: every create that answered 201
        // has made a dashboard whether or not its sibling failed, and the ones
        // that landed must be removed even when the test is about to fail.
        for (const created of all) {
          if (created.id !== null) registerDashboardCleanup(created.id);
        }

        await test.step('Every create was accepted', async () => {
          expect(
            all.map((created) => created.status),
            'four creates of two names, two pairs in flight together, all answered 201 — ' +
              'a 409 here is the slug uniqueness the fix removed, back again',
          ).toEqual([201, 201, 201, 201]);
          // The message as well as the status, because it names the regression.
          // A build that answered 409 for some other reason is a different
          // failure from this one, and the reader of the report should be able
          // to tell which they have.
          for (const created of all) {
            expect(
              created.message,
              'and none of them was refused as an existing dashboard',
            ).not.toContain(ALREADY_EXISTS);
          }
        });

        await test.step('Four distinct dashboards exist, not two with a survivor each', async () => {
          // The ids, because four 201s could in principle be two creates and two
          // idempotent re-reads of the same row. A name that is not a key means
          // four rows.
          const ids = all.map((created) => created.id);
          expect(ids, 'each create reported an id in its Location header').not.toContain(null);
          expect(new Set(ids).size, 'and the four ids are distinct').toBe(4);

          const listed = await backendClient.listDashboardsWithPrefix(sharedName);
          expect(
            listed.map((dashboard) => dashboard.id).sort(),
            'and all four are listed under the run prefix, so none was rolled back behind its 201',
          ).toEqual([...(ids as string[])].sort());
        });
      },
    );

    test(
      'repeated concurrent insights-view creates under one name never collide',
      { tag: ['@cap:dashboards.create-dashboard'] },
      async ({
        project,
        secondProject,
        backendClient,
        registerInsightsViewCleanup,
        testNamespace,
      }) => {
        const sharedName = `${testNamespace}-view`;
        const created: Array<{ status: number; message: string; id: string | null }> = [];

        // Rounds rather than six at once: the pre-9189 slug was computed from a
        // count of existing rows, so each round faced a different count — and a
        // restored constraint could plausibly survive the first pair and fail a
        // later one. Sequential rounds of concurrent PAIRS is the shape that
        // distinguishes them.
        for (let round = 0; round < CONCURRENT_ROUNDS; round++) {
          const pair = await test.step(`Round ${round + 1}: one name, both projects, together`, async () =>
            Promise.all([
              backendClient.createInsightsViewRaw({ name: sharedName, projectId: project.id }),
              backendClient.createInsightsViewRaw({
                name: sharedName,
                projectId: secondProject.id,
              }),
            ]),
          );
          for (const view of pair) {
            if (view.id !== null) registerInsightsViewCleanup(view.id);
          }
          created.push(...pair);
        }

        await test.step(`All ${CONCURRENT_ROUNDS * 2} creates were accepted`, async () => {
          expect(
            created.map((view) => view.status),
            `${CONCURRENT_ROUNDS} rounds of two concurrent creates under one name, all 201`,
          ).toEqual(new Array(CONCURRENT_ROUNDS * 2).fill(201));
          for (const view of created) {
            expect(
              view.message,
              'and none refused as an existing dashboard',
            ).not.toContain(ALREADY_EXISTS);
          }
        });

        await test.step('Each project holds its own, and sees only its own', async () => {
          // The per-project read as well as the count: an insights view is what
          // the project page's picker lists, so a create that landed under the
          // wrong project_id would satisfy the statuses above while putting one
          // project's views in another's picker. Both halves asserted, so the
          // collection length is pinned and a leak fails.
          for (const [label, scope] of [
            ['project', project],
            ['secondProject', secondProject],
          ] as const) {
            const views = (await backendClient.listInsightsViews({ projectId: scope.id })).filter(
              (view) => view.name === sharedName,
            );
            expect(
              views.map((view) => view.projectId),
              `${label} holds exactly its own ${CONCURRENT_ROUNDS} views of the shared name`,
            ).toEqual(new Array(CONCURRENT_ROUNDS).fill(scope.id));
          }
        });
      },
    );

    test(
      'a rename onto a name another dashboard already uses succeeds, and no slug is published',
      { tag: ['@cap:dashboards.create-dashboard'] },
      async ({ project, secondProject, backendClient, registerDashboardCleanup, testNamespace }) => {
        const takenName = `${testNamespace}-taken`;
        const movingName = `${testNamespace}-moving`;

        const [taken, moving] = await test.step('Two dashboards with names of their own', async () => {
          const first = await backendClient.createDashboardRaw({
            name: takenName,
            projectId: project.id,
          });
          const second = await backendClient.createDashboardRaw({
            name: movingName,
            projectId: secondProject.id,
          });
          for (const created of [first, second]) {
            if (created.id !== null) registerDashboardCleanup(created.id);
          }
          return [first, second];
        });

        await test.step('Both were created', async () => {
          // The precondition, asserted so the rename below is definitely a
          // rename ONTO a taken name rather than onto a free one.
          expect(
            [taken.status, moving.status],
            'the two seeds landed, so one name really is held by another dashboard',
          ).toEqual([201, 201]);
          expect(moving.id, 'and the one about to be renamed reported its id').not.toBeNull();
        });

        await test.step('Renaming the second onto the first\'s name is accepted', async () => {
          const renamed = await backendClient.renameDashboardRaw(moving.id!, takenName);
          expect(
            renamed.status,
            'a rename onto a name another dashboard holds answers 200 — this is the other half ' +
              'of the slug fix, and it goes through a different statement from the create',
          ).toBe(200);
          expect(
            renamed.message,
            'and is not refused as an existing dashboard',
          ).not.toContain(ALREADY_EXISTS);
        });

        await test.step('Both dashboards now hold that name, and both still exist', async () => {
          const listed = await backendClient.listDashboardsWithPrefix(takenName);
          expect(
            listed.map((dashboard) => dashboard.id).sort(),
            'the rename moved a name rather than replacing a row: two dashboards, one name',
          ).toEqual([taken.id!, moving.id!].sort());
        });

        await test.step('The payload publishes no slug', async () => {
          // The field whose removal IS the fix. Asserted on the wire keys
          // because an absent field cannot be read through the typed model —
          // and asserted on both dashboards, since only one of them has been
          // through the rename path that used to recompute the slug.
          for (const [label, id] of [
            ['the dashboard that kept its name', taken.id!],
            ['the renamed one', moving.id!],
          ] as const) {
            const fields = await backendClient.getDashboardFieldNames(id);
            expect(
              fields,
              `${label} carries a name, so this read really is seeing the payload`,
            ).toContain('name');
            expect(
              fields,
              `${label} publishes no slug — the derived unique key 9189 deleted`,
            ).not.toContain('slug');
          }
        });
      },
    );
  },
);
