import { test, expect } from '@e2e/fixtures';
import {
  ProjectDashboardsPage,
  DEFAULT_PROJECT_VIEW_ID,
  DEFAULT_PROJECT_VIEW_NAME,
  DEFAULT_PROJECT_VIEW_SECTIONS,
  PROJECT_OVERVIEW_COPY_NAME,
} from '@e2e/pom/project-dashboards.page';

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
