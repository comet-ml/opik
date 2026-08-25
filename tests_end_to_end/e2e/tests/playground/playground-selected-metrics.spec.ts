import { test, expect } from '@e2e/fixtures';
import { PlaygroundPage } from '@e2e/pom/playground.page';
import { ensureModelAvailable } from '@e2e/pom/model-availability';
import { buildConstantScoreMetric } from '@e2e/core/metrics';
import type { BackendClient } from '@e2e/core/backend';

/** Dataset items the `dataset` fixture seeds — one output row per item. */
const DATASET_ITEM_COUNT = 3;

/**
 * Rules `seedRules` creates. The metric selector lists every rule in the
 * project unfiltered, so this is also the denominator in its
 * "<n> of <m> selected" summary.
 */
const RULE_COUNT = 3;

interface SeededRule {
  /** The rule as the metric selector lists it. */
  ruleName: string;
  /** The name the score lands under on the trace — the ScoreResult's, not the rule's. */
  scoreName: string;
}

interface SeededRules {
  /** production scope, ticked in the selector. */
  picked: SeededRule;
  /** production scope, deliberately left unticked. */
  unpicked: SeededRule;
  /** experiment scope, never ticked — scores every experiment trace anyway. */
  experimentScoped: SeededRule;
}

/**
 * Three rules over one project, differing only in trigger scope and in whether
 * the test ticks them. Every one is a constant metric returning 1.0, so the
 * only variable in the result is WHICH rules ran — a 0.0 would mean a rule ran
 * on unexpected input, which is a different failure from "never ran".
 */
async function seedRules(
  backendClient: BackendClient,
  projectId: string,
  namespace: string,
): Promise<SeededRules> {
  const build = (suffix: string): SeededRule => ({
    ruleName: `${namespace}-${suffix}-rule`,
    scoreName: `${namespace}-${suffix}-score`,
  });
  const picked = build('picked');
  const unpicked = build('unpicked');
  const experimentScoped = build('expscope');

  await Promise.all(
    (
      [
        [picked, 'production'],
        [unpicked, 'production'],
        [experimentScoped, 'experiment'],
      ] as const
    ).map(([rule, triggerScope]) =>
      backendClient.createAutomationRule({
        projectId,
        name: rule.ruleName,
        samplingRate: 1,
        enabled: true,
        triggerScope,
        metric: buildConstantScoreMetric(rule.scoreName),
        arguments: { output: 'output' },
      }),
    ),
  );

  return { picked, unpicked, experimentScoped };
}

/**
 * The score names on every trace of the project, once scoring has gone quiet.
 *
 * Reads the whole project rather than looking up the traces the run is known to
 * have produced: the count of traces is itself part of the answer, and a run
 * that logged a fourth trace nobody asked for should fail here rather than go
 * unnoticed.
 */
async function settledScoreNamesPerTrace(
  backendClient: BackendClient,
  projectId: string,
  anchorScoreName: string,
): Promise<string[][]> {
  // The rows paint from the trace ids, but the traces list is a separate read
  // that can lag the write, so poll rather than asserting on the first answer.
  await expect
    .poll(
      async () => (await backendClient.listTraceIds({ projectId, size: 200 })).length,
      {
        timeout: 60_000,
        intervals: [500, 1000, 2000],
        message:
          `a dataset run over ${DATASET_ITEM_COUNT} items must log exactly ` +
          `${DATASET_ITEM_COUNT} traces in its project`,
      },
    )
    .toBe(DATASET_ITEM_COUNT);
  const traceIds = await backendClient.listTraceIds({ projectId, size: 200 });

  return Promise.all(
    traceIds.map(async (id) => {
      // Anchor on a score that is guaranteed to arrive before waiting for the
      // set to go quiet: waitForTraceScoresSettled decides "settled" from a
      // stable fingerprint, so a not-yet-scored trace looks stable from its
      // first poll and would be tallied as "nothing ever scored it".
      await backendClient.pollTraceForFeedbackScore(id, anchorScoreName, {
        timeoutMs: 120_000,
      });
      const trace = await backendClient.waitForTraceScoresSettled(id, {
        quietPeriodMs: 10_000,
        timeoutMs: 90_000,
        minScores: 1,
      });
      return trace.feedbackScores.map((s) => s.name).sort();
    }),
  );
}

/**
 * A playground dataset run is scored by the metrics the user ticked plus the
 * project's experiment-scoped rules, and by nothing else.
 *
 * `playground.run-against-dataset` is already covered by playground-smoke, but
 * that spec stops at "an experiment was created" — a run scored by the wrong
 * set of rules ships green under it. This is the axis OPIK-8059 changed: the
 * selector now defaults to nothing ticked (it used to default to all), an
 * unticked production rule no longer scores the run, and an experiment-scoped
 * rule scores it whether or not anyone picked it.
 */
test.describe('Playground — metric selection on a dataset run', { tag: ['@t2-cuj', '@area:playground'] }, () => {
  test('A dataset run is scored by the ticked rule and the experiment-scoped rule, and not by the unticked one', { tag: ['@cap:playground.run-against-dataset'] }, async ({
    dataset,
    project,
    backendClient,
    testNamespace,
    page,
    automationRulesCleanup,
  }) => {
    // One real LLM call per dataset item, then online scoring on each resulting
    // trace, then a quiet period to prove no fourth score is still in flight.
    test.setTimeout(420_000);

    const rules = await test.step('Seed three rules on the project via the API', async () =>
      seedRules(backendClient, project.id, testNamespace));

    const modelDisplayName = await test.step(
      'Ensure a model is available via the Configuration UI',
      async () => ensureModelAvailable(page),
    );

    const playground = new PlaygroundPage(page, project.id);
    let experimentCreated!: Promise<unknown>;

    await test.step('Open the Playground, configure a variant and load the dataset', async () => {
      await playground.goto();
      await playground.waitForReady();
      await playground.configureVariant(0, {
        systemPrompt: 'Always reply with the literal text OK.',
        userPrompt: '{{input}}',
        modelDisplayName,
      });
      await playground.clickRunExperiment();
      await playground.selectRunExperimentSource({ mode: 'dataset', entityName: dataset.name });
      await expect(playground.loadedSourcePill()).toBeVisible();
    });

    await test.step('The selector opens with nothing ticked', async () => {
      // The regression this pins: before OPIK-8059 the selector defaulted to
      // every rule ticked, so a dataset run silently invoked the whole project's
      // production rules. "0 of N" is the user-visible half of that fix.
      await playground.waitForMetricSelectorOpen();
      await expect(
        playground.metricSelectionSummary(),
        'a freshly loaded dataset must start with no metrics ticked',
      ).toHaveText(`0 of ${RULE_COUNT} selected`);
      await expect(playground.metricSelectorTriggerLabel('Select metrics')).toBeVisible();
    });

    await test.step(`Tick only "${rules.picked.ruleName}"`, async () => {
      await playground.toggleMetric(rules.picked.ruleName);
      await expect(playground.metricSelectionSummary()).toHaveText(`1 of ${RULE_COUNT} selected`);
      await expect(playground.metricSelectorTriggerLabel('Metrics')).toBeVisible();
      await playground.closeMetricSelector();
    });

    await test.step(`Run the dataset and wait for all ${DATASET_ITEM_COUNT} rows`, async () => {
      // Latch the experiment POST BEFORE clicking: the frontend queues
      // experiment creation independently of the batches that paint the rows,
      // so registering afterwards races the request. Match the collection
      // endpoint exactly — `items` and `finish` share the prefix.
      experimentCreated = page.waitForResponse(
        (r) =>
          /\/v1\/private\/experiments\/?$/.test(new URL(r.url()).pathname) &&
          r.request().method() === 'POST' &&
          r.ok(),
        { timeout: 180_000 },
      );
      await playground.clickReRun();
      await playground.waitForRunsComplete({
        expectedRows: DATASET_ITEM_COUNT,
        timeoutMs: 180_000,
      });
      await experimentCreated;
    });

    await test.step(
      'Every experiment trace carries exactly the ticked rule and the experiment-scoped rule',
      async () => {
        const expected = [rules.picked.scoreName, rules.experimentScoped.scoreName].sort();
        const perTrace = await settledScoreNamesPerTrace(
          backendClient,
          project.id,
          rules.picked.scoreName,
        );
        for (const names of perTrace) {
          // Equality, not containment: the unticked production rule leaking a
          // score onto the run is exactly the regression this test exists for,
          // and a `toContain` would not see it.
          expect(
            names,
            `expected only [${expected.join(', ')}] on every trace; ` +
              `'${rules.unpicked.ruleName}' was not ticked and is production-scoped, ` +
              `so it must not have scored this run`,
          ).toEqual(expected);
        }
      },
    );

    await test.step('The output rows render exactly those two chips and no third', async () => {
      // The UI half of the same claim. The chip set is what a user reads to
      // decide which metrics ran, so a backend that scored correctly while the
      // row advertised a rule that never ran is still a bug.
      await expect(
        playground.resultScoreTagsFor(rules.picked.scoreName),
        'the ticked rule renders on every output row',
      ).toHaveCount(DATASET_ITEM_COUNT);
      await expect(
        playground.resultScoreTagsFor(rules.experimentScoped.scoreName),
        'the experiment-scoped rule renders on every output row without being ticked',
      ).toHaveCount(DATASET_ITEM_COUNT);
      await expect(
        playground.resultScoreTagsFor(rules.unpicked.scoreName),
        'the unticked production rule must render nowhere',
      ).toHaveCount(0);
      await expect(
        playground.resultScoreTags(),
        `the table must carry exactly ${DATASET_ITEM_COUNT * 2} chips in total — ` +
          'any extra is a rule that scored the run uninvited',
      ).toHaveCount(DATASET_ITEM_COUNT * 2);
    });
  });

  test('A dataset run with nothing ticked is still scored by the experiment-scoped rule, and only by it', { tag: ['@cap:playground.run-against-dataset'] }, async ({
    dataset,
    project,
    backendClient,
    testNamespace,
    page,
    automationRulesCleanup,
  }) => {
    test.setTimeout(420_000);

    // The upgrade path lands here: a persisted selection of the legacy `null`
    // maps to "none ticked" (metricSelection.ts), and so does a fresh session.
    // Both production rules are enabled and at full rate, so the only reason
    // neither scores is that the run named no rules — which is the half of
    // OPIK-8059 a user is most likely to notice.
    const rules = await test.step('Seed three rules on the project via the API', async () =>
      seedRules(backendClient, project.id, testNamespace));

    const modelDisplayName = await test.step(
      'Ensure a model is available via the Configuration UI',
      async () => ensureModelAvailable(page),
    );

    const playground = new PlaygroundPage(page, project.id);
    let experimentCreated!: Promise<unknown>;

    await test.step('Open the Playground, configure a variant and load the dataset', async () => {
      await playground.goto();
      await playground.waitForReady();
      await playground.configureVariant(0, {
        systemPrompt: 'Always reply with the literal text OK.',
        userPrompt: '{{input}}',
        modelDisplayName,
      });
      await playground.clickRunExperiment();
      await playground.selectRunExperimentSource({ mode: 'dataset', entityName: dataset.name });
      await expect(playground.loadedSourcePill()).toBeVisible();
    });

    await test.step('Leave the selector untouched at "0 of 3 selected"', async () => {
      await playground.waitForMetricSelectorOpen();
      await expect(playground.metricSelectionSummary()).toHaveText(`0 of ${RULE_COUNT} selected`);
      await playground.closeMetricSelector();
    });

    await test.step(`Run the dataset and wait for all ${DATASET_ITEM_COUNT} rows`, async () => {
      experimentCreated = page.waitForResponse(
        (r) =>
          /\/v1\/private\/experiments\/?$/.test(new URL(r.url()).pathname) &&
          r.request().method() === 'POST' &&
          r.ok(),
        { timeout: 180_000 },
      );
      await playground.clickReRun();
      await playground.waitForRunsComplete({
        expectedRows: DATASET_ITEM_COUNT,
        timeoutMs: 180_000,
      });
      await experimentCreated;
    });

    await test.step(
      'Every experiment trace carries the experiment-scoped rule and nothing else',
      async () => {
        const perTrace = await settledScoreNamesPerTrace(
          backendClient,
          project.id,
          rules.experimentScoped.scoreName,
        );
        for (const names of perTrace) {
          expect(
            names,
            `with nothing ticked, only '${rules.experimentScoped.scoreName}' may score the run; ` +
              `both production rules are enabled at full rate and must still be skipped`,
          ).toEqual([rules.experimentScoped.scoreName]);
        }
      },
    );

    await test.step('Each output row renders that one chip and no other', async () => {
      await expect(
        playground.resultScoreTagsFor(rules.experimentScoped.scoreName),
      ).toHaveCount(DATASET_ITEM_COUNT);
      await expect(
        playground.resultScoreTags(),
        `the table must carry exactly ${DATASET_ITEM_COUNT} chips in total`,
      ).toHaveCount(DATASET_ITEM_COUNT);
    });
  });
});
