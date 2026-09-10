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

/** Registers a queue id for teardown the moment it exists. */
export type RegisterAnnotationQueueCleanup = (queueId: string) => void;

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
   * plain fixture has nothing to delete. The test registers the id as soon as
   * the API hands it over and this drains the registry afterwards — which still
   * runs when the test fails partway, unlike a trailing cleanup step.
   *
   * Annotation queues cascade with neither the project fixture nor the
   * run-prefix sweep in global-teardown, so without this they orphan.
   */
  registerAnnotationQueueCleanup: async ({ backendClient }, use, testInfo) => {
    const queueIds: string[] = [];

    await use((queueId: string) => {
      queueIds.push(queueId);
    });

    if (!shouldLeaveArtifacts(testInfo)) {
      while (queueIds.length) {
        const id = queueIds.pop()!;
        try {
          await backendClient.deleteAnnotationQueue(id);
        } catch (err) {
          console.warn(`[registerAnnotationQueueCleanup] delete warning for ${id}:`, err);
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
    { backendClient, project, feedbackDefinition, testNamespace },
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

    const enabledId = await backendClient.createAnnotationQueueWithAutomation({
      projectId: project.id,
      name: enabledName,
      feedbackDefinitionNames: [feedbackDefinition.name],
      automation: { enabled: true, groups },
    });
    const disabledId = await backendClient.createAnnotationQueueWithAutomation({
      projectId: project.id,
      name: disabledName,
      feedbackDefinitionNames: [feedbackDefinition.name],
      automation: { enabled: false, groups },
    });

    // Prove the seed actually holds before any browser opens. A UI assertion
    // over a fixture that silently failed to set the two states apart is a test
    // that cannot fail — it would read as coverage forever.
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

    if (!shouldLeaveArtifacts(testInfo)) {
      for (const id of [enabledId, disabledId]) {
        try {
          await backendClient.deleteAnnotationQueue(id);
        } catch (err) {
          console.warn(`[automationQueuePair] delete warning for ${id}:`, err);
        }
      }
    }
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
    { backendClient, project, feedbackDefinition, testNamespace },
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

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteAnnotationQueue(id);
      } catch (err) {
        console.warn(`[automationEditQueue] delete warning for ${id}:`, err);
      }
    }
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
