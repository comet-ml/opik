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
 * ## The two halves, and why they are two tests
 *
 * The API half is the contract: 150 lands, 151 is refused, the refusal names the
 * limit, and nothing is created.
 *
 * The UI half is the client-side stop opik#9469 added on top: the dialog's Name
 * input now carries `maxLength={PROJECT_NAME_MAX_LENGTH}`, so a user cannot type
 * a name the API would refuse in the first place. Separating them keeps each
 * reporting its own result — they can regress independently, and the backend
 * `@Size` is what makes the frontend cap safe to rely on rather than redundant.
 *
 * ## What this spec asserted before 2.2.144, and why it no longer does
 *
 * The UI half used to be `test.fail()`, asserting that the refusal toast named
 * the limit: on 2.2.142 the backend answered `{"errors":[…]}` while
 * `useProjectCreateMutation` read only `response.data.message`, so the user got
 * a bare "Request failed with status code 422". opik#9469 fixed BOTH ends of
 * that — the hook now reads the errors envelope through `getApiErrorMessage`,
 * and the input caps at 150 — which closes the defect and, in closing it, takes
 * away this spec's vector: an over-long name can no longer reach the server
 * from the dialog at all, so there is no refusal here to toast.
 *
 * The toast-carries-the-server's-reason claim is not dropped, it has an owner:
 * `project-create-duplicate-name.spec.ts` asserts it against a whitespace-only
 * name, which is still refused with an `errors` envelope and is now shown to the
 * user. What stays here is the half that spec cannot make — that the 150
 * boundary is where the API draws the line, and that the dialog draws it in the
 * same place.
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
    'the Create project dialog caps the typed name at the limit instead of letting the server refuse it',
    { tag: ['@cap:projects.create-project'] },
    async ({ backendClient, registerProjectCleanup, testNamespace, page }) => {
      const projects = new ProjectsPage(page);
      const stem = `${testNamespace}-ui-`;
      // Comfortably past the cap rather than one over it, so the assertion
      // below is about the input holding a LIMIT and not about an off-by-one in
      // this test's own arithmetic.
      const typed = nameOfExactly(stem, NAME_MAX_LENGTH + 50);
      // What the cap should leave behind, and so the name the create will
      // actually land under. Registered for teardown because this vector now
      // SUCCEEDS — the whole point of the cap is that the user is not refused.
      const kept = typed.slice(0, NAME_MAX_LENGTH);
      registerProjectCleanup(kept);

      // Installed before the first navigation: the recorder is an init script,
      // and Radix dismisses a toast on its own 5s timer — a point-in-time
      // locator could not tell "never raised" from "raised and already gone",
      // and "no refusal was shown" is part of what this asserts.
      await startRecordingToasts(page);

      await test.step('Open the Projects page and the create dialog', async () => {
        await projects.goto();
        await projects.waitForReady();
        await projects.openCreateProjectDialog();
      });

      await test.step(`Typing ${typed.length} characters leaves exactly ${NAME_MAX_LENGTH}`, async () => {
        await projects.typeNewProjectName(typed);
        // The value itself, not just its length: a cap that kept the LAST 150
        // characters, or that dropped characters from the middle, would satisfy
        // a length check while handing the user a name they never typed. The
        // run namespace lives at the front of the stem, so a tail-truncating
        // bug would also strand the project outside the teardown sweep.
        await expect(
          projects.newProjectNameInput,
          `the input stops at ${NAME_MAX_LENGTH} characters, keeping the first ones typed`,
        ).toHaveValue(kept);
      });

      const submitted = await test.step('Submit what the input kept', async () =>
        projects.submitOpenCreateDialog(),
      );

      await test.step('It was accepted, because the cap kept it inside the limit', async () => {
        // The pair that makes the cap meaningful: the first test proved 151
        // characters is refused, so a 201 here is only possible because the
        // input really did shorten the name. A build that dropped the
        // `maxLength` would send 200 characters and answer 422 at this line.
        expect(
          submitted.status,
          `the capped name is at the limit, not over it — the server answered ` +
            `${submitted.status}${submitted.message ? `: ${submitted.message}` : ''}`,
        ).toBe(201);
      });

      await test.step('The project exists under the capped name, whole', async () => {
        const found = await backendClient.listProjectsWithPrefix(stem);
        expect(
          found.map((p) => p.name),
          'exactly one project was created for this stem, under the full capped name',
        ).toEqual([kept]);
      });

      await test.step('The user was never shown a refusal', async () => {
        // The user-facing half of the claim, and the reason this reads as the
        // cap WORKING rather than merely as a create that happened to succeed.
        // Asserted over every toast raised, because a success toast is also
        // legitimate here — what must not appear is a complaint about the name.
        const toasts = await readRecordedToasts(page);
        for (const toast of toasts) {
          expect(
            toast,
            'nothing told the user their name was too long — it was capped before it could be',
          ).not.toContain(LIMIT_MESSAGE);
          expect(
            toast,
            'and no bare transport status was shown either',
          ).not.toMatch(/Request failed with status code/i);
        }
      });
    },
  );
});
