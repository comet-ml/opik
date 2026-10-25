// `prompt.fixture` rather than `@e2e/fixtures`: it sits one link PAST the index
// tip on purpose (see its own file comment), so `textPrompt` is only reachable
// by importing it directly, which is how every sibling prompt spec does it.
import { test, expect } from '../../fixtures/prompt.fixture';
import { PromptDetailPage } from '@e2e/pom/prompt-detail.page';

/**
 * The Edit sheet will not commit a version that would change nothing
 * (OPIK-8766, opik#8923).
 *
 * opik#8923 has two halves and they regress apart. The API half — a
 * `POST /v1/private/prompts/versions` whose template matches the latest answers
 * with that existing version instead of cutting a new one — is covered by
 * `prompt-playground-noop-save.spec.ts`, which saves an unchanged version from
 * the Playground and asserts the timeline does not grow. This is the other
 * half: the form never sends the request at all, because its commit control is
 * disabled while the draft still equals the active version.
 *
 * Nothing in the estate exercised it. `prompt-library-smoke.spec.ts` and the
 * two `edit*` POM methods only ever open the sheet, CHANGE something and
 * submit, so the disabled state is reached by no spec — and the POM could not
 * express it, since both of its edit helpers commit as a side effect of
 * opening.
 *
 * Why the UI half is worth its own test rather than being read off the API's:
 * a form that submitted a no-op save anyway would still leave the timeline at
 * one version, because the server now refuses to grow it. So the API
 * assertion in the sibling spec passes whether or not this control works, and
 * the user-visible symptom — a Create new version button that looks ready and
 * produces nothing a reader can see — is invisible to it.
 *
 * ## What makes the assertion discriminating
 *
 * "The button is disabled" on its own is satisfied just as well by a button
 * that is ALWAYS disabled, which would be a far worse bug than the one this
 * guards. So the control is driven through three states in one test:
 *
 *   1. opened with no edit            -> disabled
 *   2. one character typed            -> enabled
 *   3. the ORIGINAL template restored -> disabled again
 *
 * Step 3 is the one that pins the actual contract. A form tracking merely
 * "has the user touched this field" passes 1 and 2 and fails 3; only a form
 * comparing the draft against the active version's own content passes all
 * three. That comparison is what opik#8923 is about.
 *
 * The sheet is then dismissed without saving and the version count re-read from
 * the API, so the test also shows it left the prompt as it found it — a form
 * that silently committed on close would otherwise be invisible here.
 */
test.describe(
  'Prompt library — the Edit sheet declines a no-op version',
  { tag: ['@t2-cuj', '@area:prompts'] },
  () => {
    test(
      'Create new version is disabled until the draft differs from the active version, and re-disabled when it matches again',
      { tag: ['@cap:prompts.edit-creates-version'] },
      async ({ textPrompt, project, backendClient, page }) => {
        const promptId = await test.step('Resolve the prompt id the detail route wants', async () => {
          // NOT `textPrompt.id`: the Python SDK's create returns the prompt
          // VERSION id, which neither the detail route nor the versions
          // endpoint resolves. `bystanderPrompt` in the same fixture file
          // documents the same trap for its teardown.
          const id = await backendClient.findPromptIdByName(textPrompt.name, project.id);
          expect(id, `the seeded prompt '${textPrompt.name}' is findable by name`).not.toBeNull();
          return id!;
        });

        await test.step('The prompt starts at exactly one version', async () => {
          // The baseline every later count is read against. Asserted rather
          // than assumed: if the seed had cut two versions, "still one version"
          // at the end would be a claim about the wrong number.
          const versions = await backendClient.listPromptVersions(promptId);
          expect(versions, 'the SDK-seeded prompt has a single version').toHaveLength(1);
        });

        const detail = new PromptDetailPage(page);

        await test.step('Open the prompt detail page', async () => {
          await detail.goto(project.id, promptId);
          await detail.waitForReady();
        });

        const sheet = await detail.openEditSheet();
        const submit = detail.editSheetSubmit(sheet);
        const editor = detail.editSheetTemplateEditor(sheet);

        await test.step('The sheet opens on the active version with nothing to commit', async () => {
          await expect(
            editor,
            "the sheet is pre-filled with the active version's own template",
          ).toHaveValue(textPrompt.template);
          await expect(
            submit,
            'an unedited draft is identical to the active version, so there is no version to create',
          ).toBeDisabled();
        });

        await test.step('Typing a change enables it', async () => {
          // Proves the control can flip at all. Without this the disabled
          // assertion above would be satisfied by a permanently dead button.
          await editor.fill(`${textPrompt.template} Be concise.`);
          await expect(
            submit,
            'a draft that differs from the active version can be committed',
          ).toBeEnabled();
        });

        await test.step('Restoring the original template disables it again', async () => {
          // The contract, stated as the round trip: equality with the active
          // version is re-evaluated, not latched on first keystroke.
          await editor.fill(textPrompt.template);
          await expect(
            submit,
            'the draft matches the active version once more, so the control must refuse again — a form that only tracked "was edited" would stay enabled here',
          ).toBeDisabled();
        });

        await test.step('Dismissing the sheet leaves the prompt exactly as it was', async () => {
          await detail.closeEditSheet(sheet);

          const versions = await backendClient.listPromptVersions(promptId);
          expect(
            versions,
            'no version was committed by opening, editing, reverting and closing the sheet',
          ).toHaveLength(1);

          await expect(
            detail.activeVersionLabel(),
            'the page still shows the one version it opened on',
          ).toHaveText('v1');
        });
      },
    );
  },
);
