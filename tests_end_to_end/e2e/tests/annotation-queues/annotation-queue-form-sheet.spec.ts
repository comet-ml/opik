import { test, expect } from '@e2e/fixtures';
import {
  AnnotationQueuesPage,
  RETIRED_QUEUE_FORM_SUBMIT,
} from '@e2e/pom/annotation-queue.page';

/**
 * The create / edit annotation-queue sheet — `AddEditAnnotationQueueDialog`,
 * rewritten across 21 files by opik#9259 (OPIK-8809).
 *
 * **No spec in the estate opened this sheet before this one.** Every queue the
 * suite has ever made was created over REST (`backendClient.createAnnotationQueue`
 * or the SDK bridge), so the form got not even incidental smoke — and a render
 * error in the rewritten Annotator view card takes out queue creation entirely
 * for anyone using the feature. #9259 also renamed the edit submit
 * "Update queue" -> "Save changes", which no spec or POM clicked.
 *
 * ## What is asserted, and why it is not pixels
 *
 * Three facts a user would care about:
 *
 *  - the sheet OPENS and the card RENDERS. A card that threw takes the form
 *    with it, so "the submit persisted a queue" is the strongest available
 *    statement that the whole sheet mounted.
 *  - the card is in the right SAMPLE STATE. It renders a different card per
 *    state, and the state is computed from a traces query — so a project with
 *    traces must not show the no-data card, and an empty one must. That is the
 *    discriminating pair: either assertion alone passes on a card stuck in one
 *    state.
 *  - the edit submit is named "Save changes" and the old label is gone, and
 *    pressing it really writes. A spec that only looked for the new label would
 *    pass on a build rendering both.
 *
 * Persistence is read back over REST rather than off the screen, because the
 * instructions a save stores are not shown anywhere on the list — which is
 * exactly why a lost edit here would be silent.
 *
 * ## Why the Ollie actions are not asserted by name
 *
 * `AnnotatorViewCard`'s action set depends on the workspace's Ollie credit
 * balance (`ollieState`): Preview alone, Preview plus "Out of Ollie credits",
 * "Ollie is drafting…", or "Preview & edit with Ollie". A spec naming one would
 * be asserting the deployment's billing state, which moves without the product
 * changing. What IS credit-independent is the has-samples / no-samples split —
 * `hasNoSamples` short-circuits ahead of `ollieState` — so that is what these
 * tests claim, and the Ollie half is left honestly uncovered. The out-of-credits
 * banner is deliberately not proposed for the same reason.
 *
 * ## Deterministic
 *
 * Every entity is seeded over REST before the browser opens, and the queue the
 * create test makes is registered for teardown the moment its id exists —
 * queues cascade with neither the project fixture nor `global-teardown.ts`'s
 * run-prefix sweep.
 */
test.describe(
  'Annotation queues — the create and edit sheet',
  { tag: ['@t2-cuj', '@area:annotation-queues'] },
  () => {
    test(
      'the create sheet opens, renders the annotator view card and persists the queue',
      { tag: ['@cap:annotation-queues.create-queue'] },
      async ({
        queueFormSeed,
        backendClient,
        testNamespace,
        registerAnnotationQueueCleanup,
        page,
      }) => {
        test.setTimeout(180_000);
        const queues = new AnnotationQueuesPage(page);
        const newQueueName = `${testNamespace}-created-queue`;

        const sheet = await test.step('Open Create queue on a project that has traces', async () => {
          await queues.goto(queueFormSeed.projectId);
          await queues.waitForReady();
          return queues.openCreateQueueDialog();
        });

        await test.step('The Annotator view card renders, in its has-samples state', async () => {
          await sheet.expectAnnotatorViewCardRendered();
          await expect(
            sheet.annotatorViewDescription,
            'the copy names the default view an annotator is shown',
          ).toContainText('Annotators see');
          await expect(
            sheet.annotatorViewDescription,
            'and names the TRACE default view, this queue’s scope',
          ).toContainText('trace input, output and metadata as code blocks');

          const actions = await sheet.annotatorViewActionNames();
          // Not a name check — see the file header on Ollie and credits. What
          // this says is that the card found the project's traces: the no-data
          // branch offers Log traces and nothing else, and it short-circuits
          // ahead of every credit-dependent action.
          expect(actions, 'the card offers at least one action').not.toHaveLength(0);
          expect(
            actions,
            'a project with traces is not on the no-data branch',
          ).not.toContain('Log traces');
        });

        const queueId = await test.step(`Fill the name and submit "Create queue"`, async () => {
          await sheet.nameInput.fill(newQueueName);
          const id = await sheet.submitAndCaptureQueueId();
          // Registered the instant the id exists, before any assertion can
          // throw: a trailing cleanup step would be skipped by exactly the
          // failures that leave the workspace dirtiest.
          registerAnnotationQueueCleanup(id, newQueueName);
          return id;
        });

        await test.step('The queue is listed, and stored against this project', async () => {
          await queues.waitForReady();
          await expect(
            queues.queueRow(queueId),
            'the new queue has a row of its own in the list',
          ).toHaveCount(1);

          const stored = await backendClient.getAnnotationQueueForm(queueId);
          expect(stored.name, 'the stored queue carries the name that was typed').toBe(
            newQueueName,
          );
          expect(stored.projectId, 'and belongs to the project it was created from').toBe(
            queueFormSeed.projectId,
          );
          expect(stored.scope, 'the form’s default scope is traces').toBe('trace');
        });
      },
    );

    test(
      'the edit sheet hydrates, submits through "Save changes", and leaves other queues alone',
      { tag: ['@cap:annotation-queues.edit-queue'] },
      async ({ queueFormSeed, backendClient, testNamespace, page }) => {
        test.setTimeout(180_000);
        const queues = new AnnotationQueuesPage(page);
        const editedInstructions = `${testNamespace} edited instructions`;

        const sheet = await test.step('Open the seeded queue through its row’s Edit action', async () => {
          await queues.goto(queueFormSeed.projectId);
          await queues.waitForReady();
          return queues.openEditQueueDialog(queueFormSeed.queueId);
        });

        await test.step('The form hydrated onto the seeded queue', async () => {
          // The premise of the save below: a form that hydrated EMPTY would
          // make "the instructions changed" true of a write that merely filled
          // a blank field, and the name would be silently cleared with it.
          await expect(sheet.nameInput, 'the name field').toHaveValue(queueFormSeed.queueName);
          await expect(
            sheet.instructionsInput,
            'the instructions field carries what the queue stores',
          ).toHaveValue(queueFormSeed.queueInstructions);
          await sheet.expectAnnotatorViewCardRendered();
        });

        await test.step(`The submit is "Save changes", and "${RETIRED_QUEUE_FORM_SUBMIT}" is gone`, async () => {
          // Both halves. A build rendering the old label beside the new one
          // would satisfy "Save changes is there" perfectly.
          await expect(sheet.submitButton, 'the renamed submit').toBeVisible();
          await expect(
            sheet.sheet.getByRole('button', { name: RETIRED_QUEUE_FORM_SUBMIT, exact: true }),
            'the retired label is no longer rendered anywhere in the sheet',
          ).toHaveCount(0);
        });

        await test.step('Change the instructions and save', async () => {
          await sheet.instructionsInput.fill(editedInstructions);
          await sheet.saveChanges(queueFormSeed.queueId);
        });

        await test.step('The edit persisted, and only on the queue that was opened', async () => {
          const stored = await backendClient.getAnnotationQueueForm(queueFormSeed.queueId);
          expect(stored.instructions, 'the stored instructions are the edited ones').toBe(
            editedInstructions,
          );
          expect(stored.name, 'and the name the form hydrated was written back unchanged').toBe(
            queueFormSeed.queueName,
          );

          // The bystander. A PATCH aimed at the wrong id, or one the backend
          // fanned out across the project's queues, would satisfy every
          // assertion above exactly as well as the correct save does.
          const bystander = await backendClient.getAnnotationQueueForm(
            queueFormSeed.bystanderQueueId,
          );
          expect(
            bystander.instructions,
            'the queue nothing opened still holds its own instructions',
          ).toBe(queueFormSeed.bystanderInstructions);
          expect(bystander.name, 'and its own name').toBe(queueFormSeed.bystanderQueueName);
        });
      },
    );

    test(
      'on a project with no traces the annotator view card offers Log traces and no Preview',
      { tag: ['@cap:annotation-queues.create-queue'] },
      async ({ project, page }) => {
        // The other half of the discriminating pair, and the reason it uses the
        // bare `project` fixture rather than `queueFormSeed`: a fresh project
        // has no traces by construction, which is the one way to reach
        // `hasNoSamples` without stubbing a query.
        //
        // This case is cosmetic rather than silent — a reader would notice the
        // copy broken within a day — but it guards new text and a new action on
        // a path no test reaches, and it is what stops the test above passing
        // over a card frozen in its has-samples state.
        const queues = new AnnotationQueuesPage(page);

        const sheet = await test.step('Open Create queue on an empty project', async () => {
          await queues.goto(project.id);
          await queues.waitForReady();
          return queues.openCreateQueueDialog();
        });

        await test.step('The card offers Log traces and nothing else', async () => {
          await sheet.expectAnnotatorViewCardRendered();
          // Exhaustive, not a membership check: the no-data branch's whole
          // claim is that Preview and every Ollie action are ABSENT, and a
          // `toContain('Log traces')` would pass beside all of them.
          expect(
            await sheet.annotatorViewActionNames(),
            'no Preview and no Ollie action while there is nothing to preview',
          ).toEqual(['Log traces']);
        });

        await test.step('…and says so, while still naming the default view', async () => {
          await expect(
            sheet.annotatorViewDescription,
            'the no-data copy leads with the absence',
          ).toContainText('No traces yet.');
          await expect(
            sheet.annotatorViewDescription,
            'and still tells the user what annotators would be shown',
          ).toContainText('Annotators see');
          await expect(
            sheet.annotatorViewDescription,
            'naming the trace default view',
          ).toContainText('trace input, output and metadata as code blocks');
        });

        // Nothing is submitted, so there is nothing to tear down: the sheet is
        // left for the fixture teardown to discard with the page.
      },
    );
  },
);
