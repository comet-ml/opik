import { test, expect } from '@e2e/fixtures';
import { ProjectsPage } from '@e2e/pom/projects.page';

test.describe('Project deletion', { tag: ['@t2-cuj', '@area:projects'] }, () => {
  test('Deleting a project removes it from the list and its traces become unreadable', { tag: ['@cap:projects.delete-project'] }, async ({
    project,
    // Seeds a dataset + experiment scoped to `project`, so the delete runs
    // against a populated project rather than a bare one. Nothing is asserted
    // about them — see the note at the end of this test.
    experiment,
    opikTrace,
    sdkClient,
    backendClient,
    testNamespace,
    page,
  }) => {
    // A second project acts as the control. Without it, "the target is gone"
    // is consistent with a delete that wiped more than it should have — the
    // control is what proves the blast radius stopped at the target.
    const controlName = `${testNamespace}-control-proj`;
    const { control, controlTrace } = await test.step(
      'Seed a control project with its own trace',
      async () => {
        const created = await sdkClient.python.createProject({ name: controlName });
        const trace = await sdkClient.python.createTrace({
          project_name: controlName,
          name: `${testNamespace}-control-trace`,
          input: 'control input',
          output: 'control output',
        });
        return { control: created, controlTrace: trace };
      },
    );

    const projects = new ProjectsPage(page);

    await test.step('Both projects are listed before the deletion', async () => {
      await projects.goto();
      await projects.searchByName(testNamespace);
      await expect(projects.projectRow(project.id)).toBeVisible();
      await expect(projects.projectRow(control.id)).toBeVisible();
    });

    await test.step('The target project is readable and owns its trace', async () => {
      expect(await backendClient.getProject(project.id)).not.toBeNull();
      const trace = await backendClient.getTrace(opikTrace.id);
      expect(trace, 'seeded trace is readable while the project exists').not.toBeNull();
      expect(trace?.projectId).toBe(project.id);
    });

    await test.step('Delete the target project from the list', async () => {
      await projects.deleteProjectById(project.id);
      await expect(projects.projectRow(project.id)).toHaveCount(0);
      await expect(
        projects.projectRow(control.id),
        'deleting one project must not remove the others',
      ).toBeVisible();
    });

    await test.step('The deleted project survives a reload (not just a stale table)', async () => {
      await page.reload();
      await projects.waitForReady();
      await projects.searchByName(testNamespace);
      await expect(projects.projectRow(project.id)).toHaveCount(0);
      await expect(projects.projectRow(control.id)).toBeVisible();
    });

    await test.step('The project and its traces are unreadable through the API', async () => {
      expect(await backendClient.getProject(project.id), 'deleted project').toBeNull();
      expect(
        await backendClient.getTrace(opikTrace.id),
        'traces are project-scoped and go with the project',
      ).toBeNull();
    });

    await test.step('The control project and its trace are untouched', async () => {
      const survivor = await backendClient.getProject(control.id);
      expect(survivor?.name).toBe(controlName);
      expect(
        await backendClient.getTrace(controlTrace.id),
        'the control trace must survive another project being deleted',
      ).not.toBeNull();
    });

    // Deliberately NOT asserted here: the fate of the project-scoped dataset
    // and experiment seeded by the `experiment` fixture. They are not
    // cascade-deleted and remain readable through the REST API, but every
    // dataset/experiment route is nested under a project, so once the project
    // is gone the UI can only render "Failed to load the project" — the data is
    // orphaned rather than preserved. Whether that is correct is an open
    // product question (OPIK_7761 follow-up), so this spec stays neutral on it
    // instead of freezing either outcome into a passing test.

    await test.step('Cleanup: delete the control project', async () => {
      await backendClient.deleteProject(control.id);
    });
  });
});
