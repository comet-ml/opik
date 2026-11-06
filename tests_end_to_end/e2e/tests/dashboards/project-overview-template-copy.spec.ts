import { test, expect } from '@e2e/fixtures';
import {
  ProjectDashboardsPage,
  DEFAULT_PROJECT_VIEW_ID,
  DEFAULT_PROJECT_VIEW_NAME,
  DEFAULT_PROJECT_VIEW_SECTIONS,
  DEFAULT_PROJECT_VIEW_WIDGET_COUNTS,
  PROJECT_OVERVIEW_COPY_NAME,
  PROJECT_OVERVIEW_SECOND_COPY_NAME,
} from '@e2e/pom/project-dashboards.page';
import { startRecordingToasts, readRecordedToasts } from '@e2e/pom/toast-recorder';

/**
 * Every test in this file runs sequentially in one worker, and the toast-Rename
 * describe lives here rather than in a file of its own for the same reason.
 *
 * The name the app generates for a first copy is the fixed string "Project
 * overview (custom)", and `insights-views` de-duplicates its workspace-wide
 * `slug` from the name with a read-then-insert that is not atomic: two creates
 * of the same name landing together — in different projects, under the repo's
 * `fullyParallel: true` — race, and one comes back 409 "Dashboard already
 * exists" instead of taking `-2`. Reproduced on the local stack with two
 * concurrent POSTs and no browser at all, so it is the server's, not
 * Playwright's. Three tests here each create that one name, so run in parallel
 * they fail about two runs in five.
 *
 * `mode: 'default'` and not `'serial'`: these tests share no state, so a failure
 * in one must not skip the others — all that is wanted is that they never
 * overlap. Nothing else in the estate creates an insights view under an
 * un-prefixed name, so one worker is enough to settle it.
 */
test.describe.configure({ mode: 'default' });

/**
 * Saving a widget on a project's built-in "Project overview" view creates an
 * editable copy of the whole template and moves the user to it (OPIK-8928,
 * shipped in 2.2.118 by opik#9162).
 *
 * `useCustomizeTemplate` is mounted on every project Dashboards page, gated only
 * on the dashboard permissions — not on Ollie — so this ships to self-hosted
 * too, and the capability it lands in (`dashboards.edit-dashboard`) had no spec
 * at all. What makes it worth a test beyond its novelty is that one click
 * silently creates a persistent object in the user's workspace, and the section
 * the widget lands in is resolved by finding the section's id in the *template*
 * config and then applying it at the same index in a copy whose ids have all
 * been regenerated. A wrong match puts the widget somewhere the user did not ask
 * for, and nothing in the UI says so.
 *
 * Both tests add their widget from a different section — the last one and the
 * first — because defaulting to the first section is the obvious way for that
 * index match to be wrong, and a test that only ever started from section 1
 * could not tell the two apart.
 *
 * Every claim about what was stored is read back from
 * `GET /v1/private/insights-views/{id}` rather than inferred from the page:
 * `buildCopyConfig` runs the template through `regenerateAllIds`, and a rewrite
 * that came out subtly wrong still renders — it draws a card with a number in
 * it. Which is also why the first test compares the five "At a glance" numbers
 * on the copy against the same five on the built-in view.
 */
test.describe(
  'Project overview — adding a widget creates an editable copy',
  { tag: ['@t2-cuj', '@area:dashboards'] },
  () => {
    // The template renders twelve widgets, each issuing its own metrics read on
    // mount, and each test renders it twice — once as the template and once as
    // the copy.
    test.slow();

    test(
      'a widget saved on the built-in view creates a copy of the whole template with the widget in the section it came from',
      { tag: ['@cap:dashboards.edit-dashboard'] },
      async ({
        projectOverviewTraces,
        registerInsightsViewCleanup,
        backendClient,
        page,
        testNamespace,
      }) => {
        const { project, traceCount } = projectOverviewTraces;
        const widgetTitle = `${testNamespace}-widget`;
        // The section deliberately NOT first: the one the template declares last.
        const sourceSection = DEFAULT_PROJECT_VIEW_SECTIONS[2];

        await test.step('The project owns no view of its own yet', async () => {
          // So the view observed below cannot be one that was already there.
          // Asserted as the whole list rather than searched: a project that
          // already held a copy would make the name assertion meaningless.
          expect(
            (await backendClient.listInsightsViews({ projectId: project.id })).map((v) => v.name),
            'a fresh project starts with no insights views',
          ).toEqual([]);
        });

        await startRecordingToasts(page);
        const dash = new ProjectDashboardsPage(page, project.id);

        await test.step('The page opens on the read-only built-in template', async () => {
          await dash.goto();
          await dash.waitForReady();
          expect(dash.currentDashboardId(), 'the view a project resolves to by default').toBe(
            DEFAULT_PROJECT_VIEW_ID,
          );
          for (const section of DEFAULT_PROJECT_VIEW_SECTIONS) {
            await expect(
              dash.sectionTitle(section),
              `the template's "${section}" section rendered`,
            ).toBeVisible();
          }
        });

        const templateKpis = await test.step(
          'The template resolves its stats cards to the seeded numbers',
          async () => {
            const values = await dash.kpiCardValues();
            // The positive control for the comparison at the end of this test.
            // Without it, "the copy reads what the template reads" would be
            // satisfied by both of them failing to resolve anything.
            expect(values['Traces'], 'the Traces card counts the seeded traces').toBe(
              String(traceCount),
            );
            expect(values['Errors'], 'none of the seeded traces errored').toBe('0');
            return values;
          },
        );

        await test.step(`Add a widget from the "${sourceSection}" section`, async () => {
          await dash.addWidgetToSection(sourceSection, widgetTitle);
        });

        const copyId = await dash.waitForCopyDashboardId();
        registerInsightsViewCleanup(copyId);

        await test.step('The save reports which copy it went to', async () => {
          const toasts = await readRecordedToasts(page);
          expect(toasts, 'the widget save raised exactly one toast').toHaveLength(1);
          // The title only: the description below it is prose, and pinning that
          // would make this spec fail for a copy edit.
          expect(toasts[0], 'the toast names the copy it created').toMatch(
            /^Saved as "Project overview \(custom\)"/,
          );
        });

        await test.step('The page is now on the copy, by name as well as by id', async () => {
          await expect(
            dash.viewPickerShowing(PROJECT_OVERVIEW_COPY_NAME),
            'the picker shows the copy that was just created',
          ).toBeVisible();
        });

        await test.step('The stored copy reproduces the template, with the new widget in its own section', async () => {
          const copy = await backendClient.getInsightsView(copyId);
          expect(copy, `the copy ${copyId} must be readable by id`).not.toBeNull();
          const detail = copy!;

          expect(detail.name, 'the name the app gave the first copy').toBe(
            PROJECT_OVERVIEW_COPY_NAME,
          );
          // Not decoration: the project page's view picker filters on
          // `multi_project`, so a copy stored as anything else exists and is
          // unreachable from the UI that made it.
          expect(detail.type, 'the copy is the type the view picker lists').toBe('multi_project');
          expect(detail.scope, 'the copy sits in the project-scoped collection').toBe('insights');
          expect(detail.projectId, 'the copy belongs to the project it was made in').toBe(
            project.id,
          );

          expect(
            detail.sections.map((s) => s.title),
            "the template's sections, in the template's order",
          ).toEqual([...DEFAULT_PROJECT_VIEW_SECTIONS]);

          // The whole shape at once rather than "section 3 grew by one": a copy
          // that also dropped or duplicated a widget elsewhere would pass that.
          const expectedCounts = DEFAULT_PROJECT_VIEW_WIDGET_COUNTS.map((count, index) =>
            DEFAULT_PROJECT_VIEW_SECTIONS[index] === sourceSection ? count + 1 : count,
          );
          expect(
            detail.sections.map((s) => s.widgetTitles.length),
            'every template widget is reproduced, and the new one is the only addition',
          ).toEqual(expectedCounts);

          expect(
            detail.sections
              .filter((s) => s.widgetTitles.includes(widgetTitle))
              .map((s) => s.title),
            'the new widget is in the section it was added from, and in no other',
          ).toEqual([sourceSection]);

          // `regenerateAllIds` is what stops every copy in the workspace from
          // sharing the template's section ids — and it has no unit test
          // anywhere, so this is the only thing checking it.
          expect(
            detail.sections.map((s) => s.id).filter((id) => id.startsWith('template-section-')),
            "the copy's section ids are regenerated, not the template's",
          ).toEqual([]);
        });

        await test.step('…and it is the only view the project gained', async () => {
          expect(
            (await backendClient.listInsightsViews({ projectId: project.id })).map((v) => ({
              id: v.id,
              name: v.name,
            })),
            'one click created exactly one view',
          ).toEqual([{ id: copyId, name: PROJECT_OVERVIEW_COPY_NAME }]);
        });

        await test.step('The copy reads the same numbers the built-in view did', async () => {
          // The point of the comparison: the copy's widgets are id-regenerated
          // rewrites of the template's, so a card that came out pointing at the
          // wrong metric still renders a number — just not this number.
          expect(
            await dash.kpiCardValues(),
            "the copy's stats cards agree with the built-in view's, card for card",
          ).toEqual(templateKpis);
        });
      },
    );

    test(
      'a second copy takes a name of its own and leaves the first one alone',
      { tag: ['@cap:dashboards.edit-dashboard'] },
      async ({
        project,
        copyNameTakenView,
        registerInsightsViewCleanup,
        backendClient,
        page,
        testNamespace,
      }) => {
        const widgetTitle = `${testNamespace}-widget`;
        // This time the FIRST section, so the two tests between them rule out a
        // section match that is really a default-to-first.
        const sourceSection = DEFAULT_PROJECT_VIEW_SECTIONS[0];

        await test.step('The project already holds a view under the name the first copy would take', async () => {
          expect(
            (await backendClient.listInsightsViews({ projectId: project.id })).map((v) => v.name),
            'the seeded view is the only one, and it holds the generated name',
          ).toEqual([PROJECT_OVERVIEW_COPY_NAME]);
        });

        await startRecordingToasts(page);
        const dash = new ProjectDashboardsPage(page, project.id);

        await test.step('Open the built-in template', async () => {
          await dash.goto();
          await dash.waitForReady();
          expect(dash.currentDashboardId(), 'the view a project resolves to by default').toBe(
            DEFAULT_PROJECT_VIEW_ID,
          );
        });

        await test.step('The existing view is on screen, so the name it holds is known to the page', async () => {
          // Not decoration, and not a wait on nothing. `getCopyName` picks the
          // copy's name from the project's view list, and the picker reads that
          // same list — so a widget saved before it arrives is named
          // "Project overview (custom)" and the create comes back 409 against
          // the view seeded here. Seeing the view listed is the only
          // UI-observable proof that the list the de-duplication reads has
          // landed.
          await dash.openViewPicker(DEFAULT_PROJECT_VIEW_NAME);
          await expect(
            dash.viewOption(PROJECT_OVERVIEW_COPY_NAME),
            'the picker lists the view already holding the generated name',
          ).toHaveCount(1);
          await dash.dismissViewPicker();
        });

        await test.step(`Add a widget from the "${sourceSection}" section`, async () => {
          await dash.addWidgetToSection(sourceSection, widgetTitle);
        });

        const copyId = await dash.waitForCopyDashboardId();
        registerInsightsViewCleanup(copyId);

        await test.step('The copy is named "(custom 2)" rather than colliding', async () => {
          const toasts = await readRecordedToasts(page);
          expect(toasts, 'the widget save raised exactly one toast').toHaveLength(1);
          expect(toasts[0], 'the toast names the second copy').toMatch(
            /^Saved as "Project overview \(custom 2\)"/,
          );
          await expect(
            dash.viewPickerShowing(PROJECT_OVERVIEW_SECOND_COPY_NAME),
            'the picker shows the second copy',
          ).toBeVisible();
        });

        await test.step('…and carries the template with the widget in its first section', async () => {
          const copy = await backendClient.getInsightsView(copyId);
          expect(copy, `the copy ${copyId} must be readable by id`).not.toBeNull();
          const detail = copy!;

          expect(detail.name, 'the stored name of the second copy').toBe(
            PROJECT_OVERVIEW_SECOND_COPY_NAME,
          );
          expect(
            detail.sections.map((s) => s.title),
            "the template's sections, in the template's order",
          ).toEqual([...DEFAULT_PROJECT_VIEW_SECTIONS]);
          // Derived from the template's own counts rather than written out as
          // [6, 3, 4]: a literal would keep asserting the 2.2.118 template after
          // someone adds a widget to it, and pass or fail for a reason that has
          // nothing to do with where this widget landed.
          expect(
            detail.sections.map((s) => s.widgetTitles.length),
            'the widget was added to the first section and nothing else moved',
          ).toEqual(
            DEFAULT_PROJECT_VIEW_WIDGET_COUNTS.map((count, index) =>
              DEFAULT_PROJECT_VIEW_SECTIONS[index] === sourceSection ? count + 1 : count,
            ),
          );
          expect(
            detail.sections
              .filter((s) => s.widgetTitles.includes(widgetTitle))
              .map((s) => s.title),
            'the new widget is in the first section, and in no other',
          ).toEqual([sourceSection]);
        });

        await test.step('The view that already held the name is untouched', async () => {
          // The bystander. Naming the new copy correctly would also be satisfied
          // by a flow that renamed the existing view out of the way first, which
          // is a silent edit to something the user made.
          const bystander = await backendClient.getInsightsView(copyNameTakenView.id);
          expect(bystander, 'the seeded view must still exist').not.toBeNull();
          expect(bystander!.name, 'the seeded view keeps its name').toBe(
            PROJECT_OVERVIEW_COPY_NAME,
          );
          expect(
            bystander!.sections.map((s) => s.title),
            'the seeded view keeps its own single section',
          ).toEqual([`${testNamespace}-taken`]);
        });
      },
    );
  },
);

/**
 * The "Rename" action on the copy toast renames the copy the toast is about —
 * not whichever view happens to be open when it is clicked (OPIK-8928, shipped
 * in 2.2.118 by opik#9162).
 *
 * A late fix commit in that PR addressed exactly this: the toast outlives the
 * view it refers to, so `handleOpenRename` has to switch the page back to the
 * copy before opening its dialog. Nothing tested the fix. If it regresses, a
 * user who clicks Rename after moving on renames a *different* dashboard — a
 * silent, destructive edit to something they made, and one they would have no
 * reason to attribute to the widget they just added.
 *
 * So the test takes the adversarial path on purpose: it switches the view picker
 * to another dashboard in the window between the copy being created and Rename
 * being clicked. The seeded view it switches to is also the bystander — "the
 * copy took the new name" would pass equally well against a rename that had
 * written that name to every view in the project, so both names are read back by
 * id at the end.
 *
 * The three clicks after the save have to fit inside the toast's dismiss window,
 * which is Radix's 5s default, so there is nothing between them but the waits
 * that establish the state each one needs.
 */
test.describe(
  'Project overview copy — the toast Rename action',
  { tag: ['@t2-cuj', '@area:dashboards'] },
  () => {
    // The built-in template renders twelve widgets, each issuing its own metrics
    // read on mount, before the flow can start.
    test.slow();

    test(
      'renames the copy the toast is about, not the view that is open by then',
      { tag: ['@cap:dashboards.edit-dashboard'] },
      async ({
        project,
        bystanderProjectView,
        registerInsightsViewCleanup,
        backendClient,
        page,
        testNamespace,
      }) => {
        // QA: failing on staging for 2.2.119, and isolated to the detour this
        // test exists to drive. The page DOES switch back to the copy — the
        // `dashboardId` poll below passes — but the Edit view dialog opens with
        // its Name box EMPTY instead of prefilled.
        //
        // Reproduced on staging as a pair, with no Playwright assertions
        // involved: clicking the toast's Rename straight after the copy opens
        // the dialog holding "Project overview (custom)", and clicking it after
        // moving the view picker to another view opens the same dialog holding
        // "". So neither the toast nor the dialog is broken; the combination is.
        //
        // Deterministic rather than a race, which is why it is marked rather
        // than waited out: `ProjectDashboardViewDialog` reads its name through
        // `useForm`'s `defaultValues`, which React Hook Form evaluates once at
        // mount — so a dialog that mounts without the copy's name can never
        // backfill it, however long anything waits. Held for 13s in two
        // separate runs.
        //
        // The user-visible cost is the one this test's docblock describes, one
        // step further on: Rename does reach the right view, but the box the
        // user types into has lost the name it was supposed to be editing, so
        // an empty submit is a validation error and a blind retype is a rename
        // of a view whose old name is no longer on screen.
        //
        // The assertions below are the CORRECT behaviour and are left intact.
        // Remove test.fail() once the dialog prefills on this path; Playwright
        // reports it the moment this passes.
        test.fail();

        const widgetTitle = `${testNamespace}-widget`;
        const renamedTo = `${testNamespace}-renamed-view`;

        await test.step('The project holds exactly the bystander view', async () => {
          // Both halves matter later: the rename assertions compare the whole
          // list, and the view the picker switches to has to be a real view of
          // this project rather than one left over from another run.
          expect(
            (await backendClient.listInsightsViews({ projectId: project.id })).map((v) => v.name),
            'the seeded bystander is the only view in the project',
          ).toEqual([bystanderProjectView.name]);
        });

        const dash = new ProjectDashboardsPage(page, project.id);

        await test.step('Open the built-in template', async () => {
          await dash.goto();
          await dash.waitForReady();
          expect(dash.currentDashboardId(), 'the view a project resolves to by default').toBe(
            DEFAULT_PROJECT_VIEW_ID,
          );
          await expect(
            dash.viewPickerShowing(DEFAULT_PROJECT_VIEW_NAME),
            'the picker shows the built-in view',
          ).toBeVisible();
        });

        await test.step('Add a widget, which creates the copy and raises the toast', async () => {
          await dash.addWidgetToSection(DEFAULT_PROJECT_VIEW_SECTIONS[0], widgetTitle);
        });

        const copyId = await dash.waitForCopyDashboardId();
        registerInsightsViewCleanup(copyId);

        await test.step('Move to the bystander view while the toast is still up', async () => {
          // The adversarial step, and the reason this spec exists. No assertions
          // on the copy in between: everything from here to the Rename click has
          // to fit inside the toast's five-second window, so what is checked
          // first is only what the next action needs.
          await dash.openViewPicker(PROJECT_OVERVIEW_COPY_NAME);
          await dash.selectView(bystanderProjectView.name, bystanderProjectView.id);
          expect(
            dash.currentDashboardId(),
            'the page really moved off the copy before Rename was clicked',
          ).toBe(bystanderProjectView.id);
        });

        await test.step('The toast is still offering Rename, and it opens the copy', async () => {
          await expect(
            dash.copyToastRenameAction,
            'the toast outlived the navigation, as a user clicking it would need',
          ).toBeVisible();
          await dash.copyToastRenameAction.click();

          await expect
            .poll(() => dash.currentDashboardId(), {
              message: 'Rename must switch the page back to the copy the toast is about',
              timeout: 15_000,
            })
            .toBe(copyId);

          // The assertion the whole spec is for: the dialog is about the copy,
          // not about the view that was on screen when Rename was clicked.
          await expect(
            dash.editViewNameInput,
            "the rename dialog is prefilled with the copy's name, not the bystander's",
          ).toHaveValue(PROJECT_OVERVIEW_COPY_NAME);
        });

        await test.step('Renaming changes the copy', async () => {
          await dash.submitRename(renamedTo);
          await expect(
            dash.viewPickerShowing(renamedTo),
            'the picker shows the renamed copy',
          ).toBeVisible();
        });

        await test.step('…and nothing else', async () => {
          const copy = await backendClient.getInsightsView(copyId);
          expect(copy, `the copy ${copyId} must still exist`).not.toBeNull();
          expect(copy!.name, 'the copy took the new name').toBe(renamedTo);

          const bystander = await backendClient.getInsightsView(bystanderProjectView.id);
          expect(bystander, 'the bystander view must still exist').not.toBeNull();
          expect(bystander!.name, 'the bystander view keeps its name').toBe(
            bystanderProjectView.name,
          );

          // The whole collection, not just the two by id: a rename that had
          // created a third view, or taken one away, would pass both reads above.
          expect(
            (await backendClient.listInsightsViews({ projectId: project.id }))
              .map((v) => v.name)
              .sort(),
            "the project's views after the rename",
          ).toEqual([bystanderProjectView.name, renamedTo].sort());
        });
      },
    );
  },
);
