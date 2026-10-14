import { test, expect, ALERT_EVENT_TITLE, ALERT_EVENT_TYPE } from '@e2e/fixtures';
import { uuid7, type AlertTriggerConfigRef } from '@e2e/core/backend';
import { AlertsPage } from '@e2e/pom/alerts.page';

/**
 * The alerts half of opik#8481 (OPIK-6303).
 *
 * That PR moved the 427-line `FeedbackScoreConditions` out of AlertsPage into
 * `pages-shared/feedback-score-conditions/` so annotation-queue automation
 * could reuse it, and changed its whole prop contract in the move:
 * `triggerIndex`/`eventType` became `groupsPath`/`scoreSource`/`showWindow`.
 * It is ungated — every user is on the new component.
 *
 * `alerts` reads fully covered and `event-triggers` reads covered, but every
 * alerts spec in the estate drives `prompt:created`, `experiment:finished`,
 * `trace:cost`, `trace:latency` or `trace:errors`, and the two feedback-score
 * triggers are the ONLY ones that render this component at all.
 * `alert-threshold-config-validation.spec.ts` is API-level by its own header
 * and covers `threshold:*` config values, not condition groups.
 *
 * Two behaviours, each able to fail on its own:
 *
 * 1. The round trip. `groupsPath` is now a runtime path string rather than a
 *    typed field path, so a group or a window that the form no longer reaches
 *    is dropped in silence — `formTriggersToAlertTriggers` skips any condition
 *    whose threshold or window is empty, and `getAllThresholdConditionGroups…`
 *    re-buckets the flat config list by `group_index` on the way back. Both the
 *    persisted shape and the rehydrated form are asserted, because each can be
 *    right while the other is wrong. The failure a user would meet is the quiet
 *    kind: a window silently back at the 24h default is an alert that fires on
 *    the wrong aggregate, or never.
 *
 * 2. The score source. `scoreSource` decides which endpoint fills the picker —
 *    trace score names for `trace:feedback_score`, thread score names for
 *    `trace_thread:feedback_score`. A prop wired to the wrong constant offers
 *    names that cannot ever match, and the alert silently never fires.
 *
 * Driven through the UI on purpose. The API accepts grouped configs whatever
 * the form does with them — the probe for this spec confirmed a grouped write
 * round-trips at REST level on an untouched build — so an API-level test would
 * pass on precisely the build where the moved component is broken.
 */

const TRACE_SCORE_TRIGGER = ALERT_EVENT_TITLE[ALERT_EVENT_TYPE.traceFeedbackScore];
const THREAD_SCORE_TRIGGER = ALERT_EVENT_TITLE[ALERT_EVENT_TYPE.traceThreadFeedbackScore];

/** `WINDOW_OPTIONS` in pages-shared/feedback-score-conditions/constants.ts. */
const WINDOW_SECONDS = { '5 mins': '300', '7 days': '604800', '30 days': '2592000' } as const;

/**
 * Three conditions across two OR groups, no two alike in any field.
 *
 * Deliberately over-specified: if every condition shared a window, a builder
 * that wrote one condition's window into all of them would round-trip
 * perfectly. Distinct values in all four fields are what make the assertion a
 * statement about each condition rather than about the set.
 */
const CONDITIONS = [
  { group: 0, operator: '>' as const, threshold: '0.7', window: '7 days' as const },
  { group: 0, operator: '<' as const, threshold: '0.25', window: '5 mins' as const },
  { group: 1, operator: '<' as const, threshold: '0.1', window: '30 days' as const },
];

/** The config fields the editor writes, in the shape the assertions compare on. */
const comparableConfig = (config: AlertTriggerConfigRef) => ({
  type: config.type,
  groupIndex: config.groupIndex,
  name: config.configValue.name,
  operator: config.configValue.operator,
  threshold: config.configValue.threshold,
  window: config.configValue.window,
});

test.describe('Alerts — feedback score conditions', { tag: ['@t2-cuj', '@area:alerts'] }, () => {
  test(
    'every condition group, operator, threshold and window survives a save and a reopen',
    { tag: ['@cap:alerts.event-triggers'] },
    async ({ project, backendClient, uiAlertCleanup, testNamespace, page }) => {
      test.setTimeout(300_000);

      const scoreNames = [`${testNamespace}-quality`, `${testNamespace}-safety`];
      // Each condition's score, chosen so the two groups do not share one: a
      // builder that wrote the last picked name into every row would otherwise
      // still satisfy group 1.
      const conditionScores = [scoreNames[0], scoreNames[1], scoreNames[0]];
      const alertName = `${testNamespace}-fb-conditions`;
      // Declared before anything is created, so a failure mid-flow cannot skip
      // the cleanup of an alert the form may already have saved.
      uiAlertCleanup([alertName]);

      await test.step('Seed two distinct trace feedback score names in the project', async () => {
        const traceId = uuid7();
        await backendClient.createTracesBatch({
          projectName: project.name,
          traces: [{ id: traceId, name: `${testNamespace}-scored`, input: {}, output: {} }],
        });
        await backendClient.setTraceFeedbackScores({
          projectName: project.name,
          scores: scoreNames.map((name, i) => ({ traceId, name, value: 0.8 - i * 0.1 })),
        });

        // The picker is filled from this endpoint, so a seed that has not
        // landed yet renders as an empty dropdown — a UI failure several steps
        // later that says nothing about the component under test.
        await expect
          .poll(
            async () => (await backendClient.listTraceFeedbackScoreNames(project.id)).sort(),
            { timeout: 60_000, intervals: [1_000, 2_000, 5_000] },
          )
          .toEqual([...scoreNames].sort());
      });

      const alerts = new AlertsPage(page);
      const editor = await test.step('Open the create-alert form', async () => {
        await alerts.goto(project.id);
        await alerts.waitForReady();
        return alerts.openCreateForm();
      });

      await test.step('Add the trace feedback score trigger', async () => {
        await editor.fillWebhookUrl('https://example.com/e2e-fb-conditions');
        await editor.addTrigger(TRACE_SCORE_TRIGGER);
        // One group with one blank condition is what the trigger starts as
        // (`DEFAULT_FEEDBACK_SCORE_CONDITION_GROUP`).
        await expect(editor.groupRemoveButtons(ALERT_EVENT_TYPE.traceFeedbackScore)).toHaveCount(1);
      });

      await test.step('Build two OR groups: two AND conditions, then one', async () => {
        await editor.fillCondition(ALERT_EVENT_TYPE.traceFeedbackScore, 0, 0, {
          scoreName: conditionScores[0],
          operator: CONDITIONS[0].operator,
          threshold: CONDITIONS[0].threshold,
          window: CONDITIONS[0].window,
        });

        await editor.addAndCondition(ALERT_EVENT_TYPE.traceFeedbackScore, 0);
        await editor.fillCondition(ALERT_EVENT_TYPE.traceFeedbackScore, 0, 1, {
          scoreName: conditionScores[1],
          operator: CONDITIONS[1].operator,
          threshold: CONDITIONS[1].threshold,
          window: CONDITIONS[1].window,
        });

        await editor.addOrGroup(ALERT_EVENT_TYPE.traceFeedbackScore);
        await editor.fillCondition(ALERT_EVENT_TYPE.traceFeedbackScore, 1, 0, {
          scoreName: conditionScores[2],
          operator: CONDITIONS[2].operator,
          threshold: CONDITIONS[2].threshold,
          window: CONDITIONS[2].window,
        });

        await expect(
          editor.groupLabels(ALERT_EVENT_TYPE.traceFeedbackScore),
          'the builder labels the groups it is holding',
        ).toHaveText(['Group 1', 'Group 2']);
      });

      // Last, deliberately. `AlertForm` keeps renaming the alert after its
      // triggers for as long as the field still holds the name it last
      // suggested, so a name typed before the conditions were filled would be
      // overwritten by a generated one.
      await test.step('Name the alert last, then save', async () => {
        await editor.fillName(alertName);
        await expect(editor.nameInput).toHaveValue(alertName);
        await editor.submit();
      });

      const alertId = await test.step('Find the saved alert', async () => {
        const saved = await backendClient.listAlertsInProject(project.id);
        const match = saved.filter((a) => a.name === alertName);
        expect(match, `exactly one alert named ${alertName}`).toHaveLength(1);
        return match[0].id;
      });

      await test.step('The persisted trigger configs carry every group, operator and window', async () => {
        const alert = await backendClient.getAlert(alertId);
        expect(alert, 'the saved alert is readable by id').not.toBeNull();
        expect(
          alert!.triggers.map((t) => t.eventType),
          'the alert carries exactly the one trigger the form added',
        ).toEqual([ALERT_EVENT_TYPE.traceFeedbackScore]);

        const configs = alert!.triggers[0].triggerConfigs;
        // The length as well as the contents: a builder that emitted an extra
        // config — a blank row the form kept, a group written twice — would
        // still satisfy a membership check, and the alert would then fire on a
        // condition nobody entered.
        expect(configs, 'one config per condition, and no others').toHaveLength(CONDITIONS.length);
        expect(configs.map(comparableConfig)).toEqual(
          CONDITIONS.map((condition, i) => ({
            type: 'threshold:feedback_score',
            groupIndex: condition.group,
            name: conditionScores[i],
            operator: condition.operator,
            threshold: condition.threshold,
            window: WINDOW_SECONDS[condition.window],
          })),
        );
      });

      // A reopen, not a reload: the groups are reconstructed from the flat
      // config list by `getAllThresholdConditionGroupsFromTriggerConfigs`, so
      // this is the only place the re-bucketing by `group_index` is observable.
      await test.step('Reopening the editor rehydrates every row exactly as entered', async () => {
        await editor.gotoEdit(project.id, alertId);
        await expect(editor.nameInput).toHaveValue(alertName);
        await expect(
          editor.groupLabels(ALERT_EVENT_TYPE.traceFeedbackScore),
          'the reopened editor rebuilds both OR groups and no more',
        ).toHaveText(['Group 1', 'Group 2']);

        for (const [i, condition] of CONDITIONS.entries()) {
          const conditionIndex = CONDITIONS.slice(0, i).filter(
            (c) => c.group === condition.group,
          ).length;
          const row = editor.conditionRow(
            ALERT_EVENT_TYPE.traceFeedbackScore,
            condition.group,
            conditionIndex,
          );
          const where = `group ${condition.group + 1} condition ${conditionIndex + 1}`;

          await expect(row.thresholdInput, `${where} exists exactly once`).toHaveCount(1);
          await expect(row.scoreSelect, `${where} score name`).toHaveText(conditionScores[i]);
          await expect(row.operator(condition.operator), `${where} operator`).toBeChecked();
          await expect(row.thresholdInput, `${where} threshold`).toHaveValue(condition.threshold);
          await expect(row.windowSelect, `${where} window`).toHaveText(
            editor.windowTriggerText(condition.window),
          );
        }
      });
    },
  );

  test(
    'the thread trigger offers thread feedback scores and the trace trigger offers trace ones',
    { tag: ['@cap:alerts.event-triggers'] },
    async ({ project, backendClient, testNamespace, page }) => {
      test.setTimeout(300_000);

      const traceScore = `${testNamespace}-trace-only`;
      const threadScore = `${testNamespace}-thread-only`;
      const threadId = `${testNamespace}-scored-thread`;

      await test.step('Seed one trace-level score and one thread-level score, with different names', async () => {
        const [plainTraceId, threadTraceId] = [uuid7(), uuid7()];
        await backendClient.createTracesBatch({
          projectName: project.name,
          traces: [
            { id: plainTraceId, name: `${testNamespace}-plain`, input: {}, output: {} },
            { id: threadTraceId, name: `${testNamespace}-in-thread`, input: {}, output: {}, threadId },
          ],
        });
        await backendClient.setTraceFeedbackScores({
          projectName: project.name,
          scores: [{ traceId: plainTraceId, name: traceScore, value: 0.9 }],
        });

        // Thread rows are materialised asynchronously from the traces that
        // share the id, and both the close and the score address the thread by
        // it — so the seed has to wait for the row rather than assume it.
        await expect
          .poll(
            async () =>
              (await backendClient.listThreads({ projectId: project.id })).threads.map((t) => t.id),
            {
              timeout: 60_000,
              intervals: [1_000, 2_000, 5_000],
              message: 'the seeded thread must be queryable before it is closed and scored',
            },
          )
          .toEqual([threadId]);

        // A thread only accepts a score once it is closed.
        await backendClient.closeThreads({ projectName: project.name, threadIds: [threadId] });
        await backendClient.setThreadFeedbackScores({
          projectName: project.name,
          scores: [{ threadId, name: threadScore, value: 0.4 }],
        });
      });

      // Proven through the API before the browser opens. Without this, a picker
      // that offered nothing at all would satisfy every "must not be listed"
      // assertion below, and the spec would read as coverage forever.
      await test.step('Each score really is visible to its own source and not the other', async () => {
        await expect
          .poll(async () => (await backendClient.listTraceFeedbackScoreNames(project.id)), {
            timeout: 60_000,
            intervals: [1_000, 2_000, 5_000],
          })
          .toEqual([traceScore]);
        await expect
          .poll(async () => (await backendClient.listThreadFeedbackScoreNames(project.id)), {
            timeout: 60_000,
            intervals: [1_000, 2_000, 5_000],
          })
          .toEqual([threadScore]);
      });

      const alerts = new AlertsPage(page);
      const editor = await test.step('Open the create form with both feedback-score triggers', async () => {
        await alerts.goto(project.id);
        await alerts.waitForReady();
        const created = await alerts.openCreateForm();
        await created.fillWebhookUrl('https://example.com/e2e-score-source');
        await created.addTrigger(TRACE_SCORE_TRIGGER);
        await created.addTrigger(THREAD_SCORE_TRIGGER);
        return created;
      });

      await test.step('The trace trigger lists the trace score and not the thread one', async () => {
        const row = editor.conditionRow(ALERT_EVENT_TYPE.traceFeedbackScore, 0, 0);
        const listbox = await editor.openConditionScorePicker(row, testNamespace);
        await expect(
          listbox.getByRole('option', { name: traceScore, exact: true }),
          'the trace trigger offers this project\'s trace score names',
        ).toHaveCount(1);
        await expect(
          listbox.getByRole('option', { name: threadScore, exact: true }),
          'a thread score has no meaning for a trace trigger and must not be offered',
        ).toHaveCount(0);
        await editor.closeConditionScorePicker();
      });

      await test.step('The thread trigger lists the thread score and not the trace one', async () => {
        const row = editor.conditionRow(ALERT_EVENT_TYPE.traceThreadFeedbackScore, 0, 0);
        const listbox = await editor.openConditionScorePicker(row, testNamespace);
        await expect(
          listbox.getByRole('option', { name: threadScore, exact: true }),
          'the thread trigger offers this project\'s thread score names',
        ).toHaveCount(1);
        await expect(
          listbox.getByRole('option', { name: traceScore, exact: true }),
          'a trace score has no meaning for a thread trigger and must not be offered',
        ).toHaveCount(0);
        await editor.closeConditionScorePicker();
      });
    },
  );
});
