import { test as baseTest } from './export-comparison.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

export interface AutomatedQueueTraceRef {
  id: string;
  name: string;
}

export interface AutomatedQueueRef {
  id: string;
  name: string;
  projectId: string;
  projectName: string;
  /** Numerical definition the automation's first condition matches on. */
  qualityScoreName: string;
  /** Numerical definition the automation's second condition matches on. */
  safetyScoreName: string;
  /** Scored above the thresholds by the spec, so automation must route it. */
  matching: AutomatedQueueTraceRef;
  /** Scored below them, so automation must leave it out. The negative control. */
  nonMatching: AutomatedQueueTraceRef;
  /** Never scored — the spec adds it by hand, so its source must read Manual. */
  manual: AutomatedQueueTraceRef;
}

export interface AutomatedQueueFixtures {
  automatedQueue: AutomatedQueueRef;
}

/** Wide enough that every value the spec writes (1, 2, 8, 9) is in range. */
const SCORE_MIN = 0;
const SCORE_MAX = 10;

/** `quality > QUALITY_THRESHOLD AND safety < SAFETY_THRESHOLD`, one AND-ed group. */
export const QUALITY_THRESHOLD = 7;
export const SAFETY_THRESHOLD = 3;

/**
 * A trace-scoped annotation queue whose automation is already configured, plus
 * the three traces the routing spec needs and the two numerical definitions its
 * conditions name.
 *
 * The queue is seeded through the backend client rather than the create form:
 * the form's own create path is what `annotation-queue-automation-off.spec.ts`
 * is about, and driving it here would make a routing failure indistinguishable
 * from a form failure.
 *
 * Deliberately does NOT write the scores. Routing fires on a score write, so a
 * trace scored before the queue existed would never be evaluated — the writes
 * are the spec's "when", and they belong in the test body.
 */
export const test = baseTest.extend<AutomatedQueueFixtures>({
  automatedQueue: async ({ sdkClient, backendClient, project, testNamespace }, use, testInfo) => {
    const qualityScoreName = `${testNamespace}-quality`;
    const safetyScoreName = `${testNamespace}-safety`;

    const definitions = [];
    for (const name of [qualityScoreName, safetyScoreName]) {
      definitions.push(
        await sdkClient.python.createFeedbackDefinition({
          name,
          min: SCORE_MIN,
          max: SCORE_MAX,
        }),
      );
    }

    const seedTrace = async (suffix: string): Promise<AutomatedQueueTraceRef> => {
      const name = `${testNamespace}-${suffix}`;
      const created = await sdkClient.python.createTrace({
        project_name: project.name,
        name,
        input: `seed input ${suffix}`,
        output: `seed output ${suffix}`,
      });
      return { id: created.id, name: created.name };
    };

    const matching = await seedTrace('matching');
    const nonMatching = await seedTrace('non-matching');
    const manual = await seedTrace('manual');

    const queueName = `${testNamespace}-auto-queue`;
    const queueId = await backendClient.createAnnotationQueueRaw({
      projectId: project.id,
      name: queueName,
      scope: 'trace',
      commentsEnabled: true,
      feedbackDefinitionNames: [qualityScoreName, safetyScoreName],
      conditionGroups: [
        [
          { scoreName: qualityScoreName, operator: '>', value: QUALITY_THRESHOLD },
          { scoreName: safetyScoreName, operator: '<', value: SAFETY_THRESHOLD },
        ],
      ],
    });

    const ref: AutomatedQueueRef = {
      id: queueId,
      name: queueName,
      projectId: project.id,
      projectName: project.name,
      qualityScoreName,
      safetyScoreName,
      matching,
      nonMatching,
      manual,
    };
    await testInfo.attach('opik.automatedQueue', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      /** Queues don't cascade with the project, and neither do the definitions. */
      try {
        await backendClient.deleteAnnotationQueue(queueId);
      } catch (err) {
        console.warn(`[automatedQueue fixture] queue delete warning for ${queueName}:`, err);
      }
      for (const definition of definitions) {
        try {
          await sdkClient.python.deleteFeedbackDefinition({ id: definition.id });
        } catch (err) {
          console.warn(
            `[automatedQueue fixture] definition delete warning for ${definition.name}:`,
            err,
          );
        }
      }
    }
  },
});

export { expect } from './export-comparison.fixture';
