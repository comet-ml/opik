import { test as baseTest } from './summarised-datasets.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import type { AnnotationQueueAutomationCondition } from '../core/backend';

export interface AutomationQueueSeed {
  id: string;
  name: string;
}

export interface AutomationTraceSeed {
  id: string;
  name: string;
}

export interface AutomationQueuePairRef {
  projectId: string;
  projectName: string;
  feedbackDefinitionName: string;
  /** Seeded with `automation.enabled = true`. */
  enabledQueue: AutomationQueueSeed;
  /**
   * Seeded with `automation.enabled = false` but a fully-filled condition, so
   * the pill has to read `enabled` rather than "are there any conditions".
   */
  disabledQueue: AutomationQueueSeed;
  condition: AnnotationQueueAutomationCondition;
}

export interface AutomationEditQueueRef {
  id: string;
  name: string;
  projectId: string;
  projectName: string;
  feedbackDefinitionName: string;
  condition: AnnotationQueueAutomationCondition;
}

export interface AutomationRoutingRef {
  projectId: string;
  projectName: string;
  feedbackDefinitionName: string;
  /** Scored below the threshold by the spec — automation must route it. */
  matchingTrace: AutomationTraceSeed;
  /** Scored above the threshold — automation must leave it alone. */
  nonMatchingTrace: AutomationTraceSeed;
  /** Never scored; the spec adds it to the queue by hand. */
  manualTrace: AutomationTraceSeed;
  threshold: number;
  matchingScore: number;
  nonMatchingScore: number;
}

/**
 * Registers a queue id for teardown the moment it exists.
 *
 * `name` is optional and used only in the warning a failed delete logs. Callers
 * that chose the id themselves (and so have the name to hand before the request)
 * pass it; callers that read the id back off a create response do not.
 */
export type RegisterAnnotationQueueCleanup = (queueId: string, name?: string) => void;

export interface AnnotationQueueAutomationFixtures {
  automationQueuePair: AutomationQueuePairRef;
  automationEditQueue: AutomationEditQueueRef;
  automationRoutingSeed: AutomationRoutingRef;
  registerAnnotationQueueCleanup: RegisterAnnotationQueueCleanup;
}

const THRESHOLD = 0.5;
const MATCHING_SCORE = 0.2;
const NON_MATCHING_SCORE = 0.9;

export const test = baseTest.extend<AnnotationQueueAutomationFixtures>({
  /**
   * Teardown for queues whose id does not exist until the test has run.
   *
   * A queue created through the create form cannot be seeded upfront, so a
   * plain fixture has nothing to delete. The caller registers the id as soon as
   * the API hands it over and this drains the registry afterwards — which still
   * runs when the test fails partway, unlike a trailing cleanup step.
   *
   * A queue does not cascade with the project fixture, so without this it
   * survives the test. `global-teardown` does sweep queues by run prefix
   * (global-teardown.ts:53), so a leak is eventually collected — but only at
   * the end of the whole run, which leaves the queue visible to every later
   * test in it. Deleting per-test is what keeps list assertions honest.
   *
   * The seeding fixtures below register through this rather than deleting in
   * their own teardown: a fixture that creates two queues and validates them
   * before `use()` would otherwise leak the first if the second failed, since
   * post-`use()` teardown never runs for a setup that threw.
   */
  registerAnnotationQueueCleanup: async ({ backendClient }, use, testInfo) => {
    const queues: Array<{ id: string; label: string }> = [];

    await use((queueId: string, name?: string) => {
      queues.push({ id: queueId, label: name ?? queueId });
    });

    if (!shouldLeaveArtifacts(testInfo)) {
      // Reverse order, so a test that built several queues on top of each other
      // tears them down the way it made them.
      while (queues.length) {
        const { id, label } = queues.pop()!;
        try {
          await backendClient.deleteAnnotationQueue(id);
        } catch (err) {
          console.warn(`[registerAnnotationQueueCleanup] delete warning for ${label}:`, err);
        }
      }
    }
  },

  /**
   * Two queues in one project that differ only in `automation.enabled`.
   *
   * The disabled one carries a complete, valid condition on purpose: it is the
   * pair that makes the Automation column falsifiable. A pill rendering
   * "is anything configured?" instead of "is it on?" would read On for both,
   * and a pair where the Off queue had no conditions could not tell the two
   * apart.
   */
  automationQueuePair: async (
    { backendClient, project, feedbackDefinition, testNamespace, registerAnnotationQueueCleanup },
    use,
    testInfo,
  ) => {
    const condition: AnnotationQueueAutomationCondition = {
      score: feedbackDefinition.name,
      operator: '<',
      value: THRESHOLD,
    };
    const groups = [{ conditions: [condition] }];

    const enabledName = `${testNamespace}-automation-on`;
    const disabledName = `${testNamespace}-automation-off`;

    // Registered the moment each id exists rather than deleted after `use()`:
    // the readback below can throw, and a post-`use()` teardown does not run
    // for a setup that failed — which would leak whichever queue was already
    // created.
    const enabledId = await backendClient.createAnnotationQueueWithAutomation({
      projectId: project.id,
      name: enabledName,
      feedbackDefinitionNames: [feedbackDefinition.name],
      automation: { enabled: true, groups },
    });
    registerAnnotationQueueCleanup(enabledId);
    const disabledId = await backendClient.createAnnotationQueueWithAutomation({
      projectId: project.id,
      name: disabledName,
      feedbackDefinitionNames: [feedbackDefinition.name],
      automation: { enabled: false, groups },
    });
    registerAnnotationQueueCleanup(disabledId);

    // Prove the seed actually holds before any browser opens. A UI assertion
    // over a fixture that silently failed to set the two states apart is a test
    // that cannot fail — it would read as coverage forever.
    //
    // `groups` is checked as well as `enabled`, and that is the load-bearing
    // half: the Off queue's job is to carry a complete condition, so that a
    // cell rendering "is anything configured?" reads On for it and fails. A
    // disabled queue that silently lost its groups would still read Off, and
    // the test would pass while proving nothing.
    for (const [id, name, expected] of [
      [enabledId, enabledName, true],
      [disabledId, disabledName, false],
    ] as const) {
      const stored = await backendClient.getAnnotationQueueSettings(id);
      if (stored?.automation?.enabled !== expected) {
        throw new Error(
          `[automationQueuePair] '${name}' was seeded with automation.enabled=${expected} but the API ` +
            `returned ${JSON.stringify(stored?.automation)} — the fixture cannot discriminate On from Off`,
        );
      }
      if (JSON.stringify(stored?.automation?.groups) !== JSON.stringify(groups)) {
        throw new Error(
          `[automationQueuePair] '${name}' was seeded with groups ${JSON.stringify(groups)} but the API ` +
            `returned ${JSON.stringify(stored?.automation?.groups)} — the pair no longer differs in ` +
            `automation.enabled alone, so the Automation column assertion is not falsifiable`,
        );
      }
    }

    const ref: AutomationQueuePairRef = {
      projectId: project.id,
      projectName: project.name,
      feedbackDefinitionName: feedbackDefinition.name,
      enabledQueue: { id: enabledId, name: enabledName },
      disabledQueue: { id: disabledId, name: disabledName },
      condition,
    };
    await testInfo.attach('opik.automationQueuePair', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);
  },

  /**
   * A queue with automation configured and comments switched off — the two
   * things an edit has to round-trip without touching.
   *
   * `comments_enabled: false` is the load-bearing half: the queue form renders
   * no comments control, so an edit that re-defaults the field flips it back on
   * with nothing on screen to show it happened.
   */
  automationEditQueue: async (
    { backendClient, project, feedbackDefinition, testNamespace, registerAnnotationQueueCleanup },
    use,
    testInfo,
  ) => {
    const condition: AnnotationQueueAutomationCondition = {
      score: feedbackDefinition.name,
      operator: '<',
      value: THRESHOLD,
    };
    const name = `${testNamespace}-edit-automation`;

    const id = await backendClient.createAnnotationQueueWithAutomation({
      projectId: project.id,
      name,
      commentsEnabled: false,
      feedbackDefinitionNames: [feedbackDefinition.name],
      automation: { enabled: true, groups: [{ conditions: [condition] }] },
    });
    // Registered before the readback, for the reason given on the pair above.
    registerAnnotationQueueCleanup(id);

    // Same discrimination check as the pair: if the seed did not persist
    // comments_enabled=false, the "edit did not re-enable comments" assertion
    // below would be asserting against a value that was never false.
    const stored = await backendClient.getAnnotationQueueSettings(id);
    if (stored?.commentsEnabled !== false || stored.automation?.enabled !== true) {
      throw new Error(
        `[automationEditQueue] '${name}' was seeded with comments_enabled=false and automation on, but ` +
          `the API returned commentsEnabled=${stored?.commentsEnabled} ` +
          `automation=${JSON.stringify(stored?.automation)}`,
      );
    }

    const ref: AutomationEditQueueRef = {
      id,
      name,
      projectId: project.id,
      projectName: project.name,
      feedbackDefinitionName: feedbackDefinition.name,
      condition,
    };
    await testInfo.attach('opik.automationEditQueue', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);
  },

  /**
   * Three unscored traces in one project, for the routing journey.
   *
   * Left unscored deliberately: automation routes on a score arriving, so the
   * spec has to create the queue first and score afterwards. A trace scored
   * before the queue existed would prove nothing about routing.
   */
  automationRoutingSeed: async (
    { sdkClient, project, feedbackDefinition, testNamespace },
    use,
    testInfo,
  ) => {
    const seedTrace = async (suffix: string): Promise<AutomationTraceSeed> => {
      const created = await sdkClient.python.createTrace({
        project_name: project.name,
        name: `${testNamespace}-${suffix}`,
        input: `seed input ${suffix}`,
        output: `seed output ${suffix}`,
      });
      return { id: created.id, name: created.name };
    };

    const ref: AutomationRoutingRef = {
      projectId: project.id,
      projectName: project.name,
      feedbackDefinitionName: feedbackDefinition.name,
      matchingTrace: await seedTrace('match'),
      nonMatchingTrace: await seedTrace('nomatch'),
      manualTrace: await seedTrace('manual'),
      threshold: THRESHOLD,
      matchingScore: MATCHING_SCORE,
      nonMatchingScore: NON_MATCHING_SCORE,
    };
    await testInfo.attach('opik.automationRoutingSeed', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);
  },
});

export { expect } from './summarised-datasets.fixture';
