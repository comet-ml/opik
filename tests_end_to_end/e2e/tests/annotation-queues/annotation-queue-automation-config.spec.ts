import { test, expect } from '@e2e/fixtures';
import { uuid7 } from '@e2e/core/backend';

/**
 * A queue automation is stored as an `annotation_queue_router` rule: a row in
 * `automation_rules` — the table every online-evaluation rule lives in — plus a
 * row of its own for what is specific to filling a queue. The subtype widened an
 * ENUM on that shared table, and `AutomationRuleRowMapper` throws outright on a
 * router row, so the hazard is not the queue: it is the neighbour. A router row
 * that reached the evaluators API would take the whole listing down, for every
 * project in the workspace at once, and the only thing standing between the two
 * is a filter nothing in the estate exercises.
 *
 * So this is two assertions in one spec, deliberately: the automation config
 * round-trips, AND the online-evaluation API is undisturbed by the row it now
 * shares a table with. Neither is interesting without the other — a config that
 * round-trips into a table it has broken is not a passing test.
 *
 * API-level throughout: the round-trip is a storage claim, and the co-tenancy
 * one is about a listing endpoint that no page renders in the shape being
 * asserted. Deterministic and seedable from nothing.
 *
 * Note what the automation does NOT do on this build: nothing calls
 * `hasEnabledAutomation` or `findEnabledByProjects` anywhere in the backend, so
 * an enabled automation with a matching condition does not actually fill its
 * queue yet. The config is persisted; the fill is not wired. Asserting a fill
 * here would be asserting a feature that has not shipped.
 */

const SCORE_THRESHOLD = 0.5;
const MAX_ITEMS_IN_QUEUE = 50;

/**
 * A Python rule whose body never runs — no provider key is needed and nothing
 * schedules it, because what is asserted is that the rule is *listed* alongside
 * a router row, not that it scores anything.
 */
const METRIC_SOURCE = `from typing import Any
from opik.evaluation.metrics import base_metric, score_result


class Coexistence(base_metric.BaseMetric):
    def __init__(self, name: str = "coexistence"):
        self.name = name

    def score(self, input: str, **ignored: Any) -> score_result.ScoreResult:
        return score_result.ScoreResult(value=1.0, name=self.name)
`;

test.describe('Annotation queues — automation config', {
  tag: ['@t2-cuj', '@area:annotation-queues'],
}, () => {
  test(
    'A queue automation round-trips, and the automation_rules table it shares with online evaluation is undisturbed',
    { tag: ['@cap:annotation-queues.create-queue'] },
    async ({
      project,
      backendClient,
      registerAnnotationQueueCleanup,
      automationRulesCleanup,
      testNamespace,
    }) => {
      // `automationRulesCleanup` is named and never called: requesting it IS the
      // whole API. It discovers the project's rules at teardown, so the Python
      // rule created below is deleted whether or not the steps after it run.
      const queueId = uuid7();
      const queueName = `${testNamespace}-automated-queue`;
      // Namespaced so the condition names a score no other run could write, and
      // so nothing in this workspace can satisfy it by accident.
      const scoreName = `${testNamespace}-relevance`;

      await test.step('Create a queue carrying an enabled automation block', async () => {
        registerAnnotationQueueCleanup(queueId, queueName);
        const { status, message } = await backendClient.createAnnotationQueue({
          id: queueId,
          projectId: project.id,
          name: queueName,
          scope: 'trace',
          automation: {
            enabled: true,
            maxItemsInQueue: MAX_ITEMS_IN_QUEUE,
            groups: [[{ scoreName, operator: '<', value: SCORE_THRESHOLD }]],
          },
        });
        expect(status, `creating the automated queue answered: ${message}`).toBe(201);
      });

      await test.step('The whole automation block reads back as it was written', async () => {
        const automation = await backendClient.getAnnotationQueueAutomation(queueId);
        expect(automation, 'the queue must read back with an automation block').not.toBeNull();
        // Compared whole rather than field by field: a stored automation that
        // also carried a group nobody configured would satisfy every individual
        // lookup, and filling a review queue from a condition the user never
        // wrote is exactly the failure worth catching.
        expect(automation).toEqual({
          enabled: true,
          maxItemsInQueue: MAX_ITEMS_IN_QUEUE,
          groups: [[{ scoreName, operator: '<', value: SCORE_THRESHOLD }]],
        });
      });

      await test.step('The evaluators API still serves this project', async () => {
        const page = await backendClient.findAutomationRuleEvaluatorsPage({
          projectId: project.id,
        });
        expect(page.status, `project-scoped evaluators read answered: ${page.message}`).toBe(200);
        // The router rule is named after its queue, so if it leaked into the
        // evaluators listing it would appear here under exactly this name.
        expect(page.names).toEqual([]);
        expect(page.total).toBe(0);
      });

      await test.step('And still serves the workspace-wide listing', async () => {
        // The read `OnlineScoringSampler.findAll()` performs — the one a single
        // unreadable rule used to take down for every project at once. Only the
        // status is asserted: the workspace holds rules this spec did not create
        // and must not depend on.
        const page = await backendClient.findAutomationRuleEvaluatorsPage({ size: 1 });
        expect(page.status, `workspace-wide evaluators read answered: ${page.message}`).toBe(200);
      });

      const ruleName = `${testNamespace}-python-rule`;
      await test.step('An online-evaluation rule can still be created on the same project', async () => {
        const ruleId = await backendClient.createAutomationRule({
          projectId: project.id,
          name: ruleName,
          metric: METRIC_SOURCE,
          arguments: { input: 'input' },
          samplingRate: 1,
        });
        expect(ruleId, 'the new rule must come back with an id').toBeTruthy();
      });

      await test.step('The project lists exactly that rule, and not the queue router', async () => {
        const page = await backendClient.findAutomationRuleEvaluatorsPage({
          projectId: project.id,
        });
        expect(page.status, `evaluators read answered: ${page.message}`).toBe(200);
        expect(page.names).toEqual([ruleName]);
        expect(page.total).toBe(1);
      });

      await test.step('The evaluators API answers 404 for the queue id, not 500', async () => {
        // The router rule's own id is not exposed by any API — the step above is
        // the real guard, and this is the cheap extra one. What it shows is that
        // an id the evaluators API does not serve is refused cleanly rather than
        // faulting: the queue id is the id a client has in hand, and it is the
        // one it would try.
        const { status, message } = await backendClient.automationRuleEvaluatorStatus(queueId);
        expect(status, `the by-id evaluator read answered: ${message}`).toBe(404);
      });
    },
  );
});
