import { test as baseTest } from './prompt.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';
import type { PromptVersionRef } from '../core/backend';

/**
 * More versions than the prompt page's timeline holds in one page (25, from
 * `usePromptVersionsByIdInfinite`'s PAGE_SIZE), with enough left over that the
 * second page is unambiguously a second page — 31 leaves six versions reachable
 * only after a lazy load.
 */
export const VERSION_COUNT = 31;

/** The timeline's page size, mirrored from the front end's own constant. */
export const VERSION_PAGE_SIZE = 25;

export interface VersionedPromptRef {
  id: string;
  name: string;
  /** Every version, oldest first — index 0 is v1, index 30 is v31. */
  versions: PromptVersionRef[];
  /** The `vN` labels the backend assigned, oldest first. */
  labels: string[];
  /** The marker embedded in version N's template, unique per version. */
  markerFor: (versionNumber: number) => string;
}

export interface VersionedPromptFixtures {
  versionedPrompt: VersionedPromptRef;
}

const marker = (name: string, n: number): string => `${name}-marker-${n}-of-${VERSION_COUNT}`;

/**
 * A prompt with 31 committed versions, each carrying a marker unique to it.
 *
 * The marker is what makes a deep link assertable: every version of a prompt
 * renders in the same place, so a page that silently fell back to the latest
 * version looks identical to one that resolved the requested version unless the
 * templates differ. Seeded through `POST /v1/private/prompts/versions` rather
 * than the UI's edit sheet — 31 round trips through a sheet would dominate the
 * test, and creating versions is not what the spec is about.
 *
 * Teardown deletes the prompt, which takes every commit with it. The id comes
 * from `findPromptIdByName`, not from a create response: the version endpoints
 * hand back version ids, and `DELETE /v1/private/prompts/{id}` answers 404 for
 * one of those, so teardown registered with it would leak in silence.
 */
export const test = baseTest.extend<VersionedPromptFixtures>({
  versionedPrompt: async ({ backendClient, project, testNamespace }, use, testInfo) => {
    const name = `${testNamespace}-versioned`;

    const versions: PromptVersionRef[] = [];
    for (let n = 1; n <= VERSION_COUNT; n++) {
      versions.push(
        await backendClient.createPromptVersion({
          name,
          template: `Version ${n}: ${marker(name, n)}. Answer {{question}}.`,
          projectId: project.id,
          changeDescription: `seeded commit ${n}`,
        }),
      );
    }

    const id = await backendClient.findPromptIdByName(name, project.id);
    if (!id) {
      throw new Error(`[versionedPrompt fixture] could not resolve prompt id for "${name}"`);
    }

    // The labels are the backend's, not a count of the loop above: the spec
    // asserts that the page renders `version_number`, so reading them back is
    // the only way its expectations aren't just its own seeding restated. A
    // missing one means the backend stopped numbering, which no downstream
    // assertion should paper over with an index.
    const labels = versions.map((v, i) => {
      if (!v.versionNumber) {
        throw new Error(
          `[versionedPrompt fixture] version ${i + 1} of "${name}" came back with no version_number`,
        );
      }
      return v.versionNumber;
    });

    const ref: VersionedPromptRef = {
      id,
      name,
      versions,
      labels,
      markerFor: (versionNumber: number) => marker(name, versionNumber),
    };

    await testInfo.attach('opik.versionedPrompt', {
      body: JSON.stringify({ id, name, labels }, null, 2),
      contentType: 'application/json',
    });

    await use(ref);

    if (!shouldLeaveArtifacts(testInfo)) {
      try {
        await backendClient.deletePrompt(id);
      } catch (err) {
        console.warn(`[versionedPrompt fixture] delete warning for ${name}:`, err);
      }
    }
  },
});

export { expect } from './prompt.fixture';
