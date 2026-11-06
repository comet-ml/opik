import { test, expect } from '@e2e/fixtures';
import { ProjectsPage } from '@e2e/pom/projects.page';
import { readRecordedToasts, startRecordingToasts } from '@e2e/pom/toast-recorder';

/**
 * A project name is capped at 150 characters: the API refuses a longer one with
 * a readable reason, and the user is told that reason (opik#9308, OPIK-9055).
 *
 * `projects.name` is `VARCHAR(150)` and nothing limited the name, so a longer
 * one failed the INSERT with a data-truncation error and answered 500. 9308 put
 * a `@Size(max = NAME_MAX_LENGTH, message = "cannot exceed {max} characters")`
 * on `Project.name` (and on `ProjectUpdate.name`, and on 25 `project_name`
 * request fields), plus a code-point guard in `ProjectService` for callers that
 * bypass bean validation.
 *
 * ## Why this spec exists at all, given there is already a create-errors spec
 *
 * `project-create-duplicate-name.spec.ts` is the only `projects.create-project`
 * spec, and its non-409 vector is a 400-character name documented in as many
 * words as "The API declares no `@Size` on a project name". That premise is what
 * 9308 invalidated: the create now answers `422 {"errors":[…]}` rather than a
 * `message`-carrying 500, so that spec's `expect(other.message).not.toBeNull()`
 * fails. It is someone else's spec and is left alone; this one pins the
 * behaviour that replaced its assumption.
 *
 * ## The two halves, and why they are two tests
 *
 * The API half is the contract: 150 lands, 151 is refused, the refusal names the
 * limit, and nothing is created. It passes.
 *
 * The UI half is what a user actually gets, and it is RED on 2.2.142 — see the
 * comment on that test. Separating them keeps the contract assertion reporting
 * its own result instead of being swallowed by an expected failure beside it.
 */

/** `Project.NAME_MAX_LENGTH`, the limit both halves are about. */
const NAME_MAX_LENGTH = 150;

/**
 * The refusal the backend's `@Size` message renders, as the `errors` envelope
 * carries it.
 *
 * Pinned, unlike the duplicate-name spec's treatment of the server's message:
 * the whole point here is that the sentence NAMES the limit, so a copy-edit
 * that dropped the number is a regression in the thing under test rather than
 * incidental wording. The number is interpolated from the constant above so the
 * two cannot drift apart.
 */
const LIMIT_MESSAGE = `name cannot exceed ${NAME_MAX_LENGTH} characters`;

/**
 * A name of exactly `length` characters that still starts with the run's
 * namespace, so the teardown sweep and `listProjectsWithPrefix` can both find
 * it.
 *
 * Padded rather than truncated-from-a-template: the namespace is already ~40
 * characters and the limit is 150, so the stem fits with room to spare — but a
 * namespace that grew past the limit would silently produce a name that fails
 * for the wrong reason, which the assertion below catches.
 */
const nameOfExactly = (stem: string, length: number): string => {
  expect(
    stem.length,
    `the run namespace must leave room inside a ${length}-character name`,
  ).toBeLessThan(length);
  return stem + 'n'.repeat(length - stem.length);
};

test.describe('Projects — the 150-character name limit', { tag: ['@t2-cuj', '@area:projects'] }, () => {
  test(
    'the API takes a 150-character name, refuses 151 with the limit named, and creates nothing',
    { tag: ['@cap:projects.create-project'] },
    async ({ backendClient, registerProjectCleanup, testNamespace }) => {
      const stem = `${testNamespace}-len-`;
      const atLimit = nameOfExactly(stem, NAME_MAX_LENGTH);
      const overLimit = nameOfExactly(stem, NAME_MAX_LENGTH + 1);

      // The pair differs by exactly one character, which is what makes the
      // refusal below a statement about the LIMIT rather than about long names
      // in general. Asserted because both are built by the same helper and a
      // bug in it would make the two vectors indistinguishable.
      expect(atLimit.length, 'the accepted name is exactly at the limit').toBe(NAME_MAX_LENGTH);
      expect(overLimit.length, 'and the refused one is exactly one over it').toBe(
        NAME_MAX_LENGTH + 1,
      );
      expect(
        overLimit.startsWith(atLimit),
        'the two names share a stem, so they differ only in length',
      ).toBe(true);

      // Registered before either write: the delete is by exact name, and the
      // registry has to be filled on the path a FAILING test also takes.
      registerProjectCleanup(atLimit);
      registerProjectCleanup(overLimit);

      await test.step(`A name of exactly ${NAME_MAX_LENGTH} characters is created`, async () => {
        const accepted = await backendClient.createProjectRaw(atLimit);
        expect(
          accepted.status,
          `a ${NAME_MAX_LENGTH}-character name is at the limit, not over it — ` +
            `the server answered ${accepted.status}: ${accepted.body}`,
        ).toBe(201);
        // And it really exists under the name that was sent, whole. The create
        // path now strips the name, so a build that also mangled it would
        // answer 201 over a project a user cannot find.
        const found = await backendClient.listProjectsWithPrefix(stem);
        expect(
          found.map((p) => p.name),
          'exactly one project exists for this stem, under the full 150-character name',
        ).toEqual([atLimit]);
      });

      const refusal = await test.step(
        `A name of ${NAME_MAX_LENGTH + 1} characters is refused as unprocessable`,
        async () => {
          const answer = await backendClient.createProjectRaw(overLimit);
          expect(
            answer.status,
            `one character over the limit must answer 422 — the server answered ` +
              `${answer.status}: ${answer.body}`,
          ).toBe(422);
          return answer;
        },
      );

      await test.step('The refusal names the limit, in the errors envelope', async () => {
        // The envelope AND its contents. `useProjectCreateMutation` reads
        // `response.data.message`, so which key carries the reason decides
        // whether a user ever sees it — and that is the subject of the UI test
        // below. Asserted here as a fact about the API so the two tests cannot
        // disagree about what the server said.
        expect(
          refusal.errors,
          'a bean-validation refusal answers an errors array naming the field and the limit',
        ).toEqual([LIMIT_MESSAGE]);
        expect(
          refusal.message,
          'and carries no `message` field — the key every error reader in the frontend looks at',
        ).toBeNull();
      });

      await test.step('The refused name created nothing', async () => {
        // The whole population for the stem, not just "the over-long name is
        // absent": a refusal that had created the project under a truncated
        // name would satisfy a by-name lookup and is exactly the pre-9308
        // failure mode this replaced.
        const found = await backendClient.listProjectsWithPrefix(stem);
        expect(
          found.map((p) => p.name),
          'the refused create left exactly the one project the accepted name made',
        ).toEqual([atLimit]);
      });
    },
  );

  test(
    'the Create project dialog tells the user about the limit rather than echoing the HTTP status',
    { tag: ['@cap:projects.create-project'] },
    async ({ backendClient, registerProjectCleanup, testNamespace, page }) => {
      // QA: failing on staging for 2.2.142. The refusal itself is correct — 422,
      // nothing created — but the toast reads only "Request failed with status
      // code 422". The backend answers `{"errors":["name cannot exceed 150
      // characters"]}` while `useProjectCreateMutation` reads
      // `response.data.message` and falls back to axios's own `error.message`,
      // so the one sentence that would tell the user what to change is dropped.
      // Compounding it: the Name input carries no `maxLength` (Description has
      // 255), so there is no client-side stop, and both dialog buttons sit
      // inside `DialogClose` (`AddEditProjectDialog.tsx`), so the dialog closes
      // on a refusal exactly as on a success and the typed name is gone. Only
      // the toast is asserted here — the dialog closing is arguably the
      // `DialogClose` wrapper working as written, and is a separate product
      // decision from whether the user is told why.
      // Remove test.fail() once the toast carries the server's reason;
      // Playwright reports it when it does.
      test.fail();

      const projects = new ProjectsPage(page);
      const stem = `${testNamespace}-ui-`;
      const overLimit = nameOfExactly(stem, NAME_MAX_LENGTH + 1);
      registerProjectCleanup(overLimit);

      // Installed before the first navigation: the recorder is an init script,
      // and Radix dismisses a toast on its own 5s timer — a point-in-time
      // locator could not tell "never raised" from "raised and already gone".
      await startRecordingToasts(page);

      await test.step('Open the Projects page and the create dialog', async () => {
        await projects.goto();
        await projects.waitForReady();
        await projects.openCreateProjectDialog();
      });

      const submitted = await test.step(
        `Type a ${NAME_MAX_LENGTH + 1}-character name and submit it`,
        async () => projects.submitNewProject(overLimit),
      );

      await test.step('The server refused it, with the limit named', async () => {
        // The precondition for everything below: if the dialog had somehow sent
        // a shortened name, or the backend had accepted this one, the toast
        // assertion would be about the wrong request.
        expect(
          submitted.status,
          'the submit was refused as unprocessable, which is the vector under test',
        ).toBe(422);
        expect(
          submitted.errors,
          'and the response carries the sentence the user needs, in its errors envelope',
        ).toEqual([LIMIT_MESSAGE]);
      });

      await test.step('Nothing was created', async () => {
        expect(
          (await backendClient.listProjectsWithPrefix(stem)).map((p) => p.name),
          'the refused create left no project behind',
        ).toEqual([]);
      });

      await test.step('The toast names the limit', async () => {
        const toasts = await readRecordedToasts(page);
        expect(toasts, 'exactly one toast was raised by the refused submit').toHaveLength(1);
        expect(
          toasts[0],
          'the user is told what is wrong with the name they typed',
        ).toContain(LIMIT_MESSAGE);
        // Stated separately, because it is the failure actually observed rather
        // than merely the negation of the line above: a bare transport status
        // is not feedback about a name, and a build that showed BOTH would
        // still be an improvement on this one.
        expect(
          toasts[0],
          'and not the bare HTTP status, which says nothing about the 150-character limit',
        ).not.toMatch(/Request failed with status code/i);
      });
    },
  );
});
