import { test, expect } from '@e2e/fixtures';
import { ProjectsPage } from '@e2e/pom/projects.page';
import { readRecordedToasts, startRecordingToasts } from '@e2e/pom/toast-recorder';

/**
 * Creating a project with a name that is already taken shows the translated
 * message; any other failure still shows the server's own (opik#9187,
 * OPIK-5573).
 *
 * `useProjectCreateMutation` special-cases HTTP 409 into "Project with this
 * name already exists" and falls through to `response.data.message` for
 * everything else. The regression worth catching is the OPPOSITE of the fix:
 * a condition widened to every error would swallow a real server message into
 * the duplicate-name string, and a user told "that name is taken" about a
 * failure that had nothing to do with the name would retype the name forever.
 *
 * So the two halves are one test, not two: the pair is what makes either half
 * mean anything. A spec that only asserted the 409 string would pass against a
 * hook that returned it unconditionally.
 *
 * The non-409 vector is an over-long name. It is asserted to have failed, and
 * to have failed with something other than a 409, before anything is read off
 * its toast — a vector that started succeeding (or that started answering 409)
 * would otherwise quietly stop testing the fall-through while still passing.
 *
 * ## What this spec no longer compares, and where that claim went
 *
 * It used to read the server's own message off the failing response and assert
 * the toast contained it. opik#9308 (2.2.142) put a `@Size` on `Project.name`,
 * so the over-long vector moved from a `{"message":…}`-carrying 500 to a
 * `422 {"errors":[…]}` — and `useProjectCreateMutation` reads only
 * `response.data.message`, so there is no longer a server message for the toast
 * to show. That comparison was failing here against 2.2.142+.
 *
 * The claim is not dropped, it is RELOCATED to the spec whose subject it is:
 * `project-name-length-limit.spec.ts` asserts the toast must name the limit,
 * against the correct behaviour, marked `test.fail()` with the defect written
 * out. Asserting it in both places would mean two `test.fail()`s on one bug.
 *
 * What stays here is this spec's own subject, undiminished: a 409 is translated
 * exactly, and a NON-409 is not translated — which is the regression opik#9187's
 * special case could introduce. The response's shape is pinned in its place, so
 * the day the backend starts sending `message` alongside `errors` this test
 * fails and prompts someone to restore the toast comparison above.
 *
 * Both halves also assert no project was created, which neither toast says.
 *
 * The shared mutation hook is used by every create-project entry point; this
 * drives the Projects page's own dialog, which is the one a user reaches first.
 */

/**
 * The string `useProjectCreateMutation` substitutes for a 409.
 *
 * Pinned here deliberately, unlike the server's message: this one is the
 * frontend's own copy, and it being exactly this is the thing opik#9187
 * shipped.
 */
const DUPLICATE_NAME_MESSAGE = 'Project with this name already exists';

/**
 * Long enough that the write fails on the name alone.
 *
 * `Project.name` carries `@Size(max = 150)` as of opik#9308, and the column is
 * `VARCHAR(150)` behind it; 400 characters clears both comfortably. The test
 * asserts the failure rather than assuming it, and says why if it stops
 * failing. The exact limit is not pinned here on purpose — that is
 * `project-name-length-limit.spec.ts`'s subject, and this vector only needs to
 * be refused for some reason other than a name clash.
 */
const OVERLONG_NAME_LENGTH = 400;

test.describe('Projects — create errors', { tag: ['@t2-cuj', '@area:projects'] }, () => {
  test(
    'a duplicate name shows the translated message, while a different failure is not reported as one',
    { tag: ['@cap:projects.create-project'] },
    async ({ project, backendClient, testNamespace, page }) => {
      const projects = new ProjectsPage(page);
      // Installed before the first navigation: the recorder is an init script,
      // and Radix dismisses a toast on its own 5s timer — a point-in-time
      // locator could not tell "never raised" from "raised and already gone",
      // and "the duplicate-name string was NOT shown" is half of what this
      // asserts.
      await startRecordingToasts(page);

      await test.step('The name under test is genuinely taken', async () => {
        // The precondition the 409 depends on, asserted rather than assumed: a
        // fixture whose project failed to land would give a 201 below, and the
        // test would fail on a missing toast with no hint why.
        const existing = await backendClient.listProjectsWithPrefix(project.name);
        expect(
          existing.map((p) => p.name),
          'exactly one project already holds the name the dialog is about to reuse',
        ).toEqual([project.name]);
      });

      await test.step('Open the Projects page', async () => {
        await projects.goto();
        await projects.waitForReady();
        await projects.search(project.name);
        await expect(
          projects.projectRowById(project.id),
          'the seeded project is listed, so the dialog opens over a workspace that holds the name',
        ).toHaveCount(1);
      });

      const duplicate = await test.step('Submit the name that is already taken', async () => {
        await projects.openCreateProjectDialog();
        return projects.submitNewProject(project.name);
      });

      await test.step('The server refused it as a conflict', async () => {
        expect(
          duplicate.status,
          'a duplicate name must answer 409 — the whole translation is keyed on that status',
        ).toBe(409);
      });

      await test.step('The toast reads the translated message and nothing else', async () => {
        const toasts = await readRecordedToasts(page);
        expect(toasts, 'exactly one toast was raised by the duplicate submit').toHaveLength(1);
        expect(
          toasts[0],
          'the user is told the name is taken, in the frontend\'s own words',
        ).toContain(DUPLICATE_NAME_MESSAGE);
        // The translation is only a translation if the server was saying
        // something else. Without this, a backend that happened to answer this
        // exact sentence would make the assertion above pass over a hook that
        // had stopped substituting anything at all.
        expect(
          duplicate.message,
          'the 409 carried a message of its own, so the string above really is a substitution',
        ).not.toBe(DUPLICATE_NAME_MESSAGE);
      });

      await test.step('Nothing was created', async () => {
        const existing = await backendClient.listProjectsWithPrefix(project.name);
        expect(
          existing.map((p) => p.id),
          'the refused create left exactly the one project that was already there',
        ).toEqual([project.id]);
      });

      const overlongName = `${testNamespace}-${'x'.repeat(OVERLONG_NAME_LENGTH)}`;
      const other = await test.step('Submit a name that fails for another reason', async () => {
        await page.reload();
        await projects.waitForReady();
        await projects.openCreateProjectDialog();
        return projects.submitNewProject(overlongName);
      });

      await test.step('It failed, and not as a conflict', async () => {
        // Stated as two assertions because they fail for different reasons: a
        // 2xx means the vector no longer exercises an error path at all, and a
        // 409 means it exercises the SAME path as the half above and so proves
        // nothing about the fall-through.
        expect(
          other.status,
          `an ${OVERLONG_NAME_LENGTH}-character name must still be refused — if the backend now ` +
            'accepts it, this vector no longer tests the non-conflict branch and needs replacing',
        ).toBeGreaterThanOrEqual(400);
        expect(
          other.status,
          'the second vector must fail for a reason OTHER than a name conflict',
        ).not.toBe(409);
      });

      await test.step('The refusal arrives in the errors envelope, which the toast cannot read', async () => {
        // The shape, pinned in place of the message comparison this step used to
        // make — see the file header. Both halves are asserted, because it is
        // the PAIR that explains why the user is shown a transport status:
        // the server did say why, and it said it under a key the frontend's
        // `response.data.message` read cannot reach.
        expect(
          other.errors,
          'the 422 carries its reason in the errors envelope',
        ).not.toBeNull();
        expect(
          other.message,
          'and NOT under `message`, the only key useProjectCreateMutation reads — the day this ' +
            'fails, the toast can show the reason again and ' +
            'project-name-length-limit.spec.ts can drop its test.fail()',
        ).toBeNull();
      });

      await test.step('The toast does not report it as a name clash', async () => {
        // The reload reset the recorder (it is a per-document init script), so
        // this is the toast raised by the second submit alone.
        const toasts = await readRecordedToasts(page);
        expect(toasts, 'exactly one toast was raised by the second submit').toHaveLength(1);
        expect(
          toasts[0],
          'a non-conflict failure must NOT be reported as a name clash — that is the ' +
            'regression the 409 special case could introduce, and the whole reason this vector ' +
            'sits in the same test as the 409 one',
        ).not.toContain(DUPLICATE_NAME_MESSAGE);
      });

      await test.step('The second failure created nothing either', async () => {
        expect(
          await backendClient.listProjectsWithPrefix(overlongName),
          'the refused over-long name left no project behind',
        ).toEqual([]);
      });
    },
  );
});
