import { test, expect } from '@e2e/fixtures';
import { OnlineEvaluationPage } from '@e2e/pom/online-evaluation.page';
import { buildConstantScoreMetric } from '@e2e/core/metrics';

/**
 * `SliderInputControl`'s reset arrow is a LOCAL undo: it restores one field's
 * default and nothing else. Two things had to be true for that to hold inside
 * the online-evaluation rule dialog, and neither is visible on screen.
 *
 * 1. The arrow must not submit the form. `AddEditRuleDialog` is a native
 *    `<form onSubmit=…>`, and a bare `<button>` inside one defaults to
 *    `type="submit"`. On an EDIT the form is already valid, so a submitting
 *    reset saves the rule and closes the dialog — taking every other pending
 *    edit with it. The stored sampling rate is NOT the tell: the reset handler
 *    restores the default before the submit runs, so the rate the save writes
 *    is the one the user just reset to and looks untouched. What a user loses
 *    is the rest of their edit, so the evidence has to be the WRITE itself —
 *    this spec asserts on the outbound PATCH and on the dialog staying open,
 *    not on the rate.
 *
 *    `last_updated_at` would be the obvious server-side way to catch a silent
 *    save, and it does not work here: the backend leaves it at the creation
 *    time even after a PATCH that really does change `sampling_rate` (checked
 *    against this build, 1.0 -> 0.5, 204, timestamp unmoved). An assertion on
 *    it would hold whether or not the reset saved, so there is none — the
 *    PATCH observer below carries that weight instead, and the control step
 *    proves it can see a write when there is one.
 *
 * 2. The arrow must hold its place while it is at the default. It is 32px tall
 *    against a 26px value box, so rendering it only once a slider leaves its
 *    default made the whole row — and every control under it — jump by 6px the
 *    moment a value changed, and back on reset. It is now always mounted and
 *    merely `visibility: hidden`, which also keeps it out of the tab order and
 *    the accessibility tree.
 *
 * Deterministic and LLM-free by construction: a `user_defined_metric_python`
 * rule needs no provider key, and nothing here ever makes the rule run — only
 * the dialog is exercised.
 *
 * Seeded over REST rather than through the create dialog so that a create-path
 * regression cannot be mistaken for an edit-path one, and because the edit
 * dialog is where a submitting reset actually does damage (in the CREATE
 * dialog the form is incomplete, so a stray submit is refused by validation).
 */

/** Matches this rule's PATCH on both the `/opik/api` and bare `/api` mounts. */
function isRuleUpdate(url: string, ruleId: string): boolean {
  return new URL(url).pathname.endsWith(`/v1/private/automations/evaluators/${ruleId}`);
}

test.describe(
  'Online Evaluation — the sampling-rate reset arrow',
  { tag: ['@t2-cuj', '@area:online-evaluation'] },
  () => {
    test(
      'Resetting the sampling rate restores the default without saving the rule',
      { tag: ['@cap:online-evaluation.edit-rule'] },
      async ({ project, backendClient, testNamespace, automationRulesCleanup, page }) => {
        const ruleName = `${testNamespace}-reset`;

        const ruleId = await test.step('Seed a python-metric rule at the default rate', async () =>
          backendClient.createAutomationRule({
            projectId: project.id,
            name: ruleName,
            // 1.0 is the dialog's default too, which is what makes the reset
            // gesture available: the arrow only appears once the field leaves
            // its default, so the test has to start ON it.
            samplingRate: 1,
            metric: buildConstantScoreMetric(ruleName),
            arguments: { output: 'output.output' },
          }));

        await test.step('The seed really is at the default rate', async () => {
          // Before the browser opens. A rule seeded at anything but 1.0 would
          // hydrate the dialog off its default, the arrow would already be
          // visible, and the "it appears when you change the value" assertion
          // below would pass without the value ever having changed.
          const rule = await backendClient.getAutomationRule(ruleId);
          expect(rule.samplingRate, 'seeded at the default rate').toBe(1);
        });

        const onlineEval = new OnlineEvaluationPage(page);

        await test.step('Open the rule in the edit dialog', async () => {
          await onlineEval.goto(project.id);
          await onlineEval.waitForReady();
          await expect(onlineEval.ruleRow(ruleName)).toHaveCount(1);
          await onlineEval.openEditRuleDialogByName(ruleName);
          await onlineEval.expandFilteringAndSampling();
          await expect(onlineEval.samplingRateInput).toHaveValue('100');
        });

        await test.step('At the default the arrow is mounted but neither visible nor clickable', async () => {
          // Three separate claims, because a regression can break each alone:
          // dropping the button entirely, showing it when it should be hidden,
          // or hiding it in a way that still swallows clicks.
          await expect(
            onlineEval.samplingRateResetButton,
            'the reset arrow stays in the DOM at the default value',
          ).toHaveCount(1);
          await expect(
            onlineEval.samplingRateResetButton,
            'and is not visible there',
          ).toBeHidden();
          const hitTestsAsTheButton = await onlineEval.samplingRateResetButton.evaluate((b) => {
            const box = b.getBoundingClientRect();
            const hit = document.elementFromPoint(
              box.left + box.width / 2,
              box.top + box.height / 2,
            );
            return hit !== null && (hit === b || b.contains(hit));
          });
          expect(
            hitTestsAsTheButton,
            'a hidden reset arrow must not be hit-testable, or it would swallow ' +
              'clicks meant for whatever is drawn under it',
          ).toBe(false);
        });

        await test.step('Moving the rate off its default reveals the arrow', async () => {
          await onlineEval.setSamplingRatePercent(50);
          await expect(
            onlineEval.samplingRateResetButton,
            'the reset arrow appears once the field leaves its default',
          ).toBeVisible();
        });

        // Armed before the click, and never torn down: every PATCH to this rule
        // for the rest of the test lands here, which is what lets the deliberate
        // save at the end prove this recorder was working all along.
        const rulePatches: string[] = [];
        page.on('request', (request) => {
          if (request.method() === 'PATCH' && isRuleUpdate(request.url(), ruleId)) {
            rulePatches.push(request.url());
          }
        });

        await test.step('Clicking the arrow restores 100% and leaves the dialog open', async () => {
          await onlineEval.resetSamplingRate();
          await expect(
            onlineEval.dialog,
            'the dialog must still be open — a reset that submitted the form ' +
              'would have saved and closed it',
          ).toBeVisible();
          expect(
            rulePatches,
            'resetting one field must not write the rule',
          ).toEqual([]);
        });

        await test.step('The stored rate is still the seeded one', async () => {
          // Weaker than it looks, and kept only as a sanity check: the bug this
          // spec is about would ALSO leave the rate at 1.0, because the reset
          // handler restores the default before the submit runs. The PATCH
          // assertion above is what actually catches it.
          const rule = await backendClient.getAutomationRule(ruleId);
          expect(rule.samplingRate, 'the stored rate is untouched').toBe(1);
        });

        await test.step('Control: a deliberate save IS seen by the same observer', async () => {
          // Without this the assertion above is unfalsifiable. "No PATCH was
          // sent" reads exactly the same way if the listener were mis-wired, if
          // the URL predicate never matched, or if the reset click had missed
          // the button entirely. Saving on purpose — same dialog, same test,
          // same recorder — shows the observer can see a write when there is
          // one, which is what gives the empty list above its meaning.
          const answered = page.waitForResponse(
            (response) =>
              response.request().method() === 'PATCH' && isRuleUpdate(response.url(), ruleId),
          );
          await onlineEval.submitRuleDialog();
          expect((await answered).ok(), 'the deliberate save was accepted').toBe(true);

          expect(
            rulePatches,
            'exactly one PATCH in the whole test — the one this step asked for. ' +
              'Two would mean the reset arrow sent one as well',
          ).toHaveLength(1);
        });
      },
    );

    test(
      'The sampling-rate row is the same height whether or not the reset arrow shows',
      { tag: ['@cap:online-evaluation.edit-rule'] },
      async ({ project, backendClient, testNamespace, automationRulesCleanup, page }) => {
        const ruleName = `${testNamespace}-steady`;

        await test.step('Seed a python-metric rule at the default rate', async () => {
          await backendClient.createAutomationRule({
            projectId: project.id,
            name: ruleName,
            samplingRate: 1,
            metric: buildConstantScoreMetric(ruleName),
            arguments: { output: 'output.output' },
          });
        });

        const onlineEval = new OnlineEvaluationPage(page);

        await test.step('Open the rule in the edit dialog', async () => {
          await onlineEval.goto(project.id);
          await onlineEval.waitForReady();
          await onlineEval.openEditRuleDialogByName(ruleName);
          await onlineEval.expandFilteringAndSampling();
          await expect(onlineEval.samplingRateInput).toHaveValue('100');
        });

        /**
         * Heights, never positions. The dialog's own scroll offset differs
         * between the two reads, so the row's `top` legitimately moves while
         * its height — the thing that pushed the controls below it around — does
         * not.
         */
        const measure = async () =>
          onlineEval.samplingRateControlRow.evaluate((row) => {
            const button = row.querySelector('button');
            if (!button) return { rowHeight: row.getBoundingClientRect().height, button: null };
            const box = button.getBoundingClientRect();
            return {
              rowHeight: row.getBoundingClientRect().height,
              button: { width: box.width, height: box.height },
            };
          });

        const atDefault = await test.step('Measure the row at the default value', measure);

        await test.step('The arrow reserves its box even while hidden', async () => {
          // The claim that makes the height comparison below mean something. A
          // row that measured the same in both states because the button had
          // been removed from BOTH would satisfy an equality on its own; this
          // says the hidden button is really there, really sized, and really
          // the thing holding the row open.
          expect(
            atDefault.button,
            'the reset arrow is mounted at the default value, not conditionally rendered',
          ).not.toBeNull();
          // Non-zero, not a pinned 32×32. What has to be true is that the
          // hidden button really occupies a box — a zero-sized one would hold
          // the row open by nothing, and the equality below would then pass
          // over two rows neither of which reserved anything. The exact size is
          // a styling choice no user would notice changing.
          expect(atDefault.button!.width, 'the hidden arrow reserves width').toBeGreaterThan(0);
          expect(atDefault.button!.height, 'and height').toBeGreaterThan(0);
        });

        const offDefault = await test.step('Measure the row with the rate moved off default', async () => {
          await onlineEval.setSamplingRatePercent(50);
          await expect(onlineEval.samplingRateResetButton).toBeVisible();
          return measure();
        });

        await test.step('Nothing about the row changed except the arrow becoming visible', async () => {
          expect(
            offDefault.button,
            'the arrow occupies the same box once visible as it did while hidden',
          ).toEqual(atDefault.button);
          expect(
            offDefault.rowHeight,
            'the control row must not grow when the reset arrow appears — it did, ' +
              'by 6px, when the arrow was rendered only off the default, and every ' +
              'control below it moved down and back',
          ).toBe(atDefault.rowHeight);
        });
      },
    );
  },
);
