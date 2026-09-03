import { test as baseTest } from './prompt.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import type { PromptVersionRef } from '../core/backend';

/**
 * The page size the version-history sidebar asks for
 * (`usePromptVersionsByIdInfinite`'s `PAGE_SIZE`). Not configurable from the
 * outside — a spec that wants to prove the sidebar paginates has to know the
 * boundary it is crossing.
 */
export const VERSION_HISTORY_PAGE_SIZE = 25;

/** Enough versions to need a second page, with room to spare on the first. */
export const VERSIONED_PROMPT_VERSION_COUNT = 30;

/**
 * The environment the deploy fixture points at a version. `development`,
 * `staging` and `production` are seeded into every workspace's registry.
 */
export const DEPLOY_ENVIRONMENT = 'development';

/**
 * A version that sits on the *second* page of history — deployed state on it is
 * only visible to a menu that pages far enough to find it.
 */
export const DEPLOYED_VERSION_LABEL = 'v3';

export interface VersionedPromptRef {
  id: string;
  name: string;
  /** Newest first — `['v30', 'v29', …, 'v1']`, the order the sidebar renders. */
  labels: string[];
  /** Newest first, same order as `labels`. */
  versions: PromptVersionRef[];
}

export interface DeployedVersionRef {
  version: PromptVersionRef;
  environment: string;
}

export interface VersionedPromptFixtures {
  versionedPrompt: VersionedPromptRef;
  deployedOldVersion: DeployedVersionRef;
}

/**
 * What version N's template says, so a spec can tell which version's content it
 * is looking at. Deliberately not `v<digits>`: a spec asserts which version
 * labels the compare dialog renders by scanning its text for them, and template
 * content that looked like a label would be indistinguishable from one.
 */
export const versionMarker = (n: number): string => `marker${n}`;

export const versionTemplate = (n: number): string =>
  `Prompt revision number ${n}. Answer as ${versionMarker(n)}: {{question}}`;

export const test = baseTest.extend<VersionedPromptFixtures>({
  /**
   * One prompt carrying `VERSIONED_PROMPT_VERSION_COUNT` versions, committed
   * one at a time through the public prompt-version endpoint (the same write
   * the UI's "Create new version" makes).
   *
   * Sequential on purpose: versions are ordered by `created_at`, and firing the
   * writes concurrently would leave the order of same-millisecond commits — the
   * thing every assertion here is about — up to the database.
   */
  versionedPrompt: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const name = `${testNamespace}-versioned`;
    let promptId = '';
    for (let n = 1; n <= VERSIONED_PROMPT_VERSION_COUNT; n += 1) {
      const created = await backendClient.createPromptVersion({
        name,
        template: versionTemplate(n),
        projectId: project.id,
        changeDescription: `Seeded revision ${n}`,
      });
      promptId = created.promptId;
    }

    // Read the seed back before any test looks at the UI: a sidebar assertion
    // over a prompt that silently ended up with the wrong number of versions —
    // or with labels that don't run v30..v1 — is a test that cannot fail.
    const expectedLabels = Array.from(
      { length: VERSIONED_PROMPT_VERSION_COUNT },
      (_, i) => `v${VERSIONED_PROMPT_VERSION_COUNT - i}`,
    );
    const { total, versions } = await backendClient.listPromptVersions(promptId, {
      size: VERSIONED_PROMPT_VERSION_COUNT,
    });
    const labels = versions.map((v) => v.label);
    if (total !== VERSIONED_PROMPT_VERSION_COUNT || labels.join(',') !== expectedLabels.join(',')) {
      throw new Error(
        `[versionedPrompt fixture] expected ${VERSIONED_PROMPT_VERSION_COUNT} versions labelled ` +
          `${expectedLabels.join(',')}, got total=${total} labels=${labels.join(',')}`,
      );
    }

    const ref: VersionedPromptRef = { id: promptId, name, labels, versions };
    await testInfo.attach('opik.versioned-prompt', {
      body: JSON.stringify({ id: ref.id, name: ref.name, labels: ref.labels }, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        // Deleting the prompt takes its versions and their environment
        // assignments with it; prompts do not cascade with the project, and
        // global-teardown's run-prefix sweep does not reach them.
        await backendClient.deletePrompt(promptId);
      } catch (err) {
        console.warn(`[versionedPrompt fixture] delete warning for ${name}:`, err);
      }
    }
  },

  /**
   * Deploys `DEPLOYED_VERSION_LABEL` — a version on the second page of history
   * — to `DEPLOY_ENVIRONMENT`, and asserts server-side that it is the only
   * version of the prompt with any environment on it. Without that check a
   * "only one row says Currently" assertion could pass on a prompt where
   * nothing was ever deployed.
   */
  deployedOldVersion: async ({ backendClient, versionedPrompt }, use, testInfo) => {
    const target = versionedPrompt.versions.find((v) => v.label === DEPLOYED_VERSION_LABEL);
    if (!target) {
      throw new Error(
        `[deployedOldVersion fixture] ${DEPLOYED_VERSION_LABEL} is not among the seeded versions ` +
          `(${versionedPrompt.labels.join(',')})`,
      );
    }
    await backendClient.setPromptVersionEnvironments(target.id, [DEPLOY_ENVIRONMENT]);

    const { versions } = await backendClient.listPromptVersions(versionedPrompt.id, {
      size: VERSIONED_PROMPT_VERSION_COUNT,
    });
    const deployed = versions.filter((v) => v.environments.length > 0);
    const isOnlyTarget =
      deployed.length === 1 &&
      deployed[0].label === DEPLOYED_VERSION_LABEL &&
      deployed[0].environments.join(',') === DEPLOY_ENVIRONMENT;
    if (!isOnlyTarget) {
      throw new Error(
        `[deployedOldVersion fixture] expected only ${DEPLOYED_VERSION_LABEL} on ` +
          `${DEPLOY_ENVIRONMENT}, got ` +
          `${deployed.map((v) => `${v.label}=[${v.environments.join('|')}]`).join(', ') || 'nothing deployed'}`,
      );
    }

    const ref: DeployedVersionRef = { version: target, environment: DEPLOY_ENVIRONMENT };
    await testInfo.attach('opik.deployed-old-version', {
      body: JSON.stringify({ label: target.label, environment: DEPLOY_ENVIRONMENT }, null, 2),
      contentType: 'application/json',
    });

    // No teardown of its own: the assignment lives on the version, which goes
    // when versionedPrompt deletes the prompt.
    await use(ref);
  },
});

export { expect } from './prompt.fixture';
