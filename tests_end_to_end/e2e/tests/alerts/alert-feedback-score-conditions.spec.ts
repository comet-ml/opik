import { test, expect, ALERT_EVENT_TITLE, ALERT_EVENT_TYPE } from '@e2e/fixtures';
import { AlertsPage } from '@e2e/pom/alerts.page';
import {
  ALERT_WINDOW_SECONDS,
  AlertEditorPage,
  type FeedbackScoreCondition,
} from '@e2e/pom/alert-editor.page';

const TRACE_FEEDBACK_SCORE = ALERT_EVENT_TITLE[ALERT_EVENT_TYPE.traceFeedbackScore];

/**
 * The copy the alerts form passes as `minimumMessage`, rather than the shared
 * component's own default ("Can't remove — at least one group with one
 * condition is required."). Asserted verbatim: a caller that stops passing the
 * prop still shows *a* tooltip, so only the exact wording distinguishes the two.
 */
const MINIMUM_MESSAGE =
  "Can't remove — every alert needs at least one group with at least one condition.";

test.describe('Alerts — feedback score conditions', { tag: ['@t2-cuj', '@area:alerts'] }, () => {
  test(
    "A feedback-score trigger's OR groups and AND conditions survive save and reopen",
    { tag: ['@cap:alerts.event-triggers'] },
    async ({ project, scoredTraces, uiAlertCleanup, backendClient, testNamespace, page }) => {
      const [scoreA, scoreB] = scoredTraces.scoreNames;
      const name = `${testNamespace}-alert-fs-groups`;
      // Declared before the create, so a failure mid-flow cannot skip cleanup.
      uiAlertCleanup([name]);
      const webhookUrl = 'https://example.com/e2e-webhook-fs-groups';

      /**
       * Two OR groups of two AND conditions each, with every field different
       * from every other row's. That is deliberate: the builder addresses each
       * field through a path string it assembles at runtime, and rows sharing a
       * value would compare equal however badly they were shuffled.
       */
      const GROUPS: FeedbackScoreCondition[][] = [
        [
          { score: scoreA, operator: '>', threshold: '0.8', window: '24 hours' },
          { score: scoreB, operator: '<', threshold: '0.3', window: '1 hour' },
        ],
        [
          { score: scoreB, operator: '>', threshold: '0.5', window: '6 hours' },
          { score: scoreA, operator: '<', threshold: '0.9', window: '7 days' },
        ],
      ];

      const alerts = new AlertsPage(page);

      await test.step('Open the alerts list on the seeded project', async () => {
        await alerts.goto(project.id);
        await alerts.waitForReady();
      });

      const editor = await test.step('Open the create form', async () => alerts.openCreateForm());

      await test.step('Name the alert and add the trace feedback-score trigger', async () => {
        await editor.fillName(name);
        await editor.fillWebhookUrl(webhookUrl);
        await editor.addTrigger(TRACE_FEEDBACK_SCORE);
      });

      const conditions = editor.feedbackScoreConditions(
        ALERT_EVENT_TYPE.traceFeedbackScore,
        0,
      );

      await test.step('Verify the trigger opens on one empty, windowed condition', async () => {
        await expect(conditions.groups).toHaveCount(1);
        await expect(conditions.conditions(0)).toHaveCount(1);
        await expect(conditions.scoreSelect(0, 0)).toHaveText('Select score');
        await expect(conditions.operatorOption(0, 0, '>')).toBeChecked();
        await expect(conditions.thresholdInput(0, 0)).toHaveValue('');
        // The 86400 default the alert-only version of this component had.
        await expect(conditions.windowSelect(0, 0)).toHaveText('In the last 24 hours');
      });

      await test.step('Fill group 1 with two AND-ed conditions', async () => {
        await conditions.fillCondition(0, 0, GROUPS[0][0]);
        await conditions.addCondition(0);
        await conditions.fillCondition(0, 1, GROUPS[0][1]);
      });

      await test.step('Add a second OR group and fill its two conditions', async () => {
        await conditions.addGroup();
        await conditions.fillCondition(1, 0, GROUPS[1][0]);
        await conditions.addCondition(1);
        await conditions.fillCondition(1, 1, GROUPS[1][1]);
      });

      await test.step('Verify the four conditions are separated by AND, OR, AND', async () => {
        await expect(conditions.groups).toHaveCount(2);
        await expect(conditions.conditions(0)).toHaveCount(2);
        await expect(conditions.conditions(1)).toHaveCount(2);
        await expect(conditions.orSeparators).toHaveCount(1);
        await expect(conditions.andSeparators(0)).toHaveCount(1);
        await expect(conditions.andSeparators(1)).toHaveCount(1);
      });

      const alertId = await test.step('Submit and find the new alert on the list', async () => {
        await editor.submit();
        await alerts.waitForReady();
        return alerts.alertIdByName(name);
      });

      await test.step('Reopen the alert and verify every condition came back on its own row', async () => {
        const reopened = new AlertEditorPage(page);
        await reopened.gotoEdit(project.id, alertId);
        await expect(reopened.nameInput).toHaveValue(name);

        const reloaded = reopened.feedbackScoreConditions(
          ALERT_EVENT_TYPE.traceFeedbackScore,
          0,
        );
        await expect(reloaded.groups).toHaveCount(2);
        await expect(reloaded.conditions(0)).toHaveCount(2);
        await expect(reloaded.conditions(1)).toHaveCount(2);

        /**
         * Compared as a set of groups rather than group 0 against `GROUPS[0]`,
         * because nothing in the round trip promises a position. `AlertDAO.FIND`
         * aggregates the configs with `JSON_ARRAYAGG` and no `ORDER BY`, and the
         * form hydrates them by bucketing into a `Map` keyed on `group_index`
         * and returning `Array.from(buckets.values())` — first-seen order, never
         * sorted (`AddEditAlertPage/helpers.ts`). So the row order is whatever
         * order the rows came back in. OR groups are a set and AND conditions
         * commute, so that is not a defect — but a positional assertion would
         * pass on today's row order and flake the day it changes.
         *
         * This still pins everything the test is about: all four rows carry the
         * fields they were given, and the *bucketing* survives — a condition
         * that moved between groups, or a field that landed on another row, is
         * a difference here. That is what the deliberately all-different values
         * above are for.
         */
        const canonicalGroups = (groups: readonly FeedbackScoreCondition[][]) =>
          groups
            .map((group) =>
              group
                .map((c) =>
                  JSON.stringify([c.score, c.operator, c.threshold, c.window]),
                )
                .sort(),
            )
            .map((group) => JSON.stringify(group))
            .sort();

        expect(canonicalGroups(await reloaded.readGroups())).toEqual(
          canonicalGroups(GROUPS),
        );
      });

      // The form could round-trip its own state and still be writing the wrong
      // payload: `group_index` is what makes two conditions AND-ed rather than
      // OR-ed, and no screen renders it.
      await test.step('Verify the persisted trigger configs carry the right group indexes', async () => {
        const persisted = await backendClient.getAlert(alertId);
        expect(persisted.triggers).toHaveLength(1);

        const trigger = persisted.triggers[0];
        expect(trigger.eventType).toBe(ALERT_EVENT_TYPE.traceFeedbackScore);
        expect(trigger.configs).toHaveLength(4);

        const expectedConfigs = GROUPS.flatMap((group, groupIndex) =>
          group.map((condition) => ({
            type: 'threshold:feedback_score',
            groupIndex,
            configValue: {
              name: condition.score,
              operator: condition.operator,
              threshold: condition.threshold,
              window: ALERT_WINDOW_SECONDS[condition.window],
            },
          })),
        );

        // Compared as whole collections, not by finding each expected row: a
        // config the form should never have written would otherwise pass
        // unnoticed. `AlertDAO.FIND` aggregates with no ORDER BY and the
        // backend serialises `config_value` in its own key order, so both sides
        // are normalised rather than either order being assumed. Nothing is
        // dropped in normalising — an unexpected key still shows up as a
        // difference.
        const canonical = (
          configs: readonly {
            type: string;
            // Nullable on the API side (a legacy singleton group), so it is
            // compared as it arrives rather than coerced to a number.
            groupIndex: number | null;
            configValue: Record<string, string>;
          }[],
        ) =>
          configs
            .map((config) =>
              JSON.stringify({
                type: config.type,
                groupIndex: config.groupIndex,
                configValue: Object.fromEntries(
                  Object.entries(config.configValue).sort(([a], [b]) => a.localeCompare(b)),
                ),
              }),
            )
            .sort();
        expect(canonical(trigger.configs)).toEqual(canonical(expectedConfigs));
      });
    },
  );

  test(
    'The condition builder keeps at least one group with one condition',
    { tag: ['@cap:alerts.event-triggers'] },
    async ({ project, page }) => {
      // No alert is ever submitted here — these are the builder's own
      // client-side structural rules — so there is nothing to clean up.
      const alerts = new AlertsPage(page);

      await test.step('Open the create form on an empty project', async () => {
        await alerts.goto(project.id);
        await alerts.waitForReady();
      });

      const editor = await test.step('Open the create form', async () => alerts.openCreateForm());

      await test.step('Add the trace feedback-score trigger', async () => {
        await editor.addTrigger(TRACE_FEEDBACK_SCORE);
      });

      const conditions = editor.feedbackScoreConditions(
        ALERT_EVENT_TYPE.traceFeedbackScore,
        0,
      );

      await test.step('Build a second group holding two conditions', async () => {
        await conditions.addGroup();
        await conditions.addCondition(1);
        await expect(conditions.groups).toHaveCount(2);
        await expect(conditions.conditions(1)).toHaveCount(2);
      });

      await test.step('Removing one of two conditions leaves the group standing', async () => {
        await conditions.removeCondition(1, 1);
        await expect(conditions.groups).toHaveCount(2);
        await expect(conditions.conditions(1)).toHaveCount(1);
      });

      // The rule that is easy to get wrong in the other direction: removing the
      // last condition must take the group with it, not leave an empty one.
      await test.step("Removing a group's last condition removes the group", async () => {
        await conditions.removeCondition(1, 0);
        await expect(conditions.groups).toHaveCount(1);
        await expect(conditions.groupLabel(0)).toBeVisible();
        await expect(conditions.conditions(0)).toHaveCount(1);
      });

      await test.step('The last group and its last condition cannot be removed', async () => {
        await expect(conditions.removeGroupButton(0)).toBeDisabled();
        await expect(conditions.removeConditionButton(0, 0)).toBeDisabled();
      });

      await test.step('Both disabled controls explain themselves in the alerts wording', async () => {
        expect(await conditions.disabledTooltipText(conditions.removeGroupButton(0))).toBe(
          MINIMUM_MESSAGE,
        );
        expect(await conditions.disabledTooltipText(conditions.removeConditionButton(0, 0))).toBe(
          MINIMUM_MESSAGE,
        );
      });
    },
  );
});
