import { test as baseTest } from './annotation-queue-automation-ui.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import {
  uuid7,
  type AnnotationQueueAutomationSeed,
  type BackendClient,
} from '../core/backend';

export interface AutomatedQueueRef {
  id: string;
  name: string;
  projectId: string;
  projectName: string;
  /** Exactly what was sent on create — the baseline every "unchanged" assertion compares against. */
  automation: AnnotationQueueAutomationSeed;
}

/**
 * Named for the queues it seeds rather than for the feature, because
 * `AnnotationQueueAutomationFixtures` is already the UI fixture's name one link
 * down the chain and both are re-exported from `fixtures/index.ts`.
 *
 * `registerAnnotationQueueCleanup` is deliberately absent: it lives on
 * `annotation-queue-automation-ui.fixture`, which this extends, so the specs
 * here inherit the same registry the UI specs use rather than a second one that
 * would drain independently.
 */
export interface AutomatedQueueFixtures {
  automatedQueue: AutomatedQueueRef;
  bystanderAutomatedQueue: AutomatedQueueRef;
}

/**
 * Two groups, three operators, two scores in one group: the smallest shape that
 * can tell "stored and returned faithfully" apart from "returned something
 * automation-shaped". A single group of one condition would survive a backend
 * that dropped every group past the first, or collapsed a conjunction.
 */
const SEED_AUTOMATION: AnnotationQueueAutomationSeed = {
  enabled: true,
  conditions: {
    groups: [
      {
        conditions: [
          { score: 'relevance', operator: '>', value: 0.5 },
          { score: 'toxicity', operator: '<', value: 0.2 },
        ],
      },
      { conditions: [{ score: 'verdict', operator: '=', value: 1 }] },
    ],
  },
};

/** Deliberately distinguishable from SEED_AUTOMATION, so a mix-up between the two fails. */
const BYSTANDER_AUTOMATION: AnnotationQueueAutomationSeed = {
  enabled: true,
  conditions: {
    groups: [{ conditions: [{ score: 'bystander-score', operator: '=', value: 1 }] }],
  },
};

/**
 * Seed one queue carrying an automation config, through REST.
 *
 * REST rather than the SDK bridge because the public Python SDK's
 * `create_traces_annotation_queue` has no automation parameter — the field is
 * new in opik#8258 and reachable only over the API today.
 *
 * The id is chosen here (`uuid7()`) rather than read back from the response:
 * `POST /v1/private/annotation-queues` answers 201 with no body, so a
 * server-chosen id would be unknown to teardown if anything about the response
 * were unexpected. (`global-teardown.ts` does sweep annotation queues by run
 * prefix, so a leak is reclaimed at the end of the run rather than permanent —
 * but only for a name that carries the prefix, and only once the whole run is
 * over, which is too late for an in-run `total`-shaped assertion.)
 *
 * A non-201 deletes before it throws, because the failure mode is not
 * hypothetical: a create whose `automation` the backend rejects answers 400
 * *having already written the queue row* (queue first, automation second — the
 * two are in different stores and the write is not transactional).
 */
async function seedAutomatedQueue(args: {
  backendClient: BackendClient;
  projectId: string;
  projectName: string;
  name: string;
  automation: AnnotationQueueAutomationSeed;
}): Promise<AutomatedQueueRef> {
  const id = uuid7();
  const created = await args.backendClient.createAnnotationQueue({
    id,
    projectId: args.projectId,
    name: args.name,
    automation: args.automation,
  });
  if (created.status !== 201) {
    try {
      await args.backendClient.deleteAnnotationQueue(id);
    } catch {
      // Swallowed deliberately: the create's status is the diagnosis, and a
      // delete that 404s is the ordinary case (nothing was written).
    }
    throw new Error(
      `seeding annotation queue '${args.name}' answered ${created.status}: ${created.message}`,
    );
  }
  return {
    id,
    name: args.name,
    projectId: args.projectId,
    projectName: args.projectName,
    automation: args.automation,
  };
}

export const test = baseTest.extend<AutomatedQueueFixtures>({
  automatedQueue: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const name = `${testNamespace}-automated-queue`;
    const ref = await seedAutomatedQueue({
      backendClient,
      projectId: project.id,
      projectName: project.name,
      name,
      automation: SEED_AUTOMATION,
    });

    // The fixture has to prove it set the state up, not just that the write
    // was accepted: every assertion downstream is about an automation the
    // queue is supposed to be holding, so a seed that silently stored none
    // would produce a spec that cannot fail.
    const stored = await backendClient.getAnnotationQueueRecord(ref.id);
    if (stored?.automation == null) {
      throw new Error(`annotation queue '${name}' was created without the automation it was sent`);
    }

    await testInfo.attach('opik.automatedQueue', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteAnnotationQueue(ref.id);
      } catch (err) {
        console.warn(`[automatedQueue fixture] delete warning for ${name}:`, err);
      }
    }
  },

  /**
   * A second automated queue in the same project that no test touches, so
   * "the target's automation is gone" cannot also be satisfied by a delete
   * that took every automation row in the project with it.
   */
  bystanderAutomatedQueue: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const name = `${testNamespace}-automated-bystander`;
    const ref = await seedAutomatedQueue({
      backendClient,
      projectId: project.id,
      projectName: project.name,
      name,
      automation: BYSTANDER_AUTOMATION,
    });

    await testInfo.attach('opik.bystanderAutomatedQueue', {
      body: JSON.stringify(ref, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deleteAnnotationQueue(ref.id);
      } catch (err) {
        console.warn(`[bystanderAutomatedQueue fixture] delete warning for ${name}:`, err);
      }
    }
  },

});

export { expect } from './annotation-queue-automation-ui.fixture';
