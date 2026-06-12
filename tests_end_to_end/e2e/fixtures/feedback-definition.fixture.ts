import { test as baseTest } from './test-suite.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

export interface FeedbackDefinitionRef {
  id: string;
  name: string;
  min: number;
  max: number;
}

export interface FeedbackDefinitionFixtures {
  feedbackDefinition: FeedbackDefinitionRef;
}

const MIN = 0;
const MAX = 1;

/**
 * A numerical feedback definition, the precondition for the trace panel's
 * manual named-score editor (the UI only renders an editable score control for
 * definitions that exist in the workspace). Workspace-scoped, so it does not
 * cascade with a project — this fixture deletes it explicitly on teardown. The
 * name is namespaced per test to avoid collisions across parallel workers.
 */
export const test = baseTest.extend<FeedbackDefinitionFixtures>({
  feedbackDefinition: async ({ sdkClient, testNamespace }, use, testInfo) => {
    const name = `${testNamespace}-quality`;
    const created = await sdkClient.python.createFeedbackDefinition({ name, min: MIN, max: MAX });

    const ref: FeedbackDefinitionRef = { id: created.id, name: created.name, min: MIN, max: MAX };
    await testInfo.attach('opik.feedbackDefinition', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await sdkClient.python.deleteFeedbackDefinition({ id: created.id });
      } catch (err) {
        console.warn(`[feedbackDefinition fixture] delete warning for ${name}:`, err);
      }
    }
  },
});

export { expect } from './test-suite.fixture';
