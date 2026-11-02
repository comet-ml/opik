import { test as baseTest } from './sme-internal-thread-queue.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

export interface OtelRoutedProjectsFixtures {
  registerProjectCleanup: (name: string) => void;
}

/**
 * Cleanup for projects the BACKEND created, not the test.
 *
 * OTLP ingestion resolves `opik.project_name` get-or-create, so a routed export
 * brings projects into existence as a side effect of the write under test. Their
 * ids do not exist until the export has already landed, which rules out a seed
 * fixture: the test can only hand over a NAME, and only after the fact.
 *
 * Registered by name rather than by id for the same reason. The id has to be
 * looked up before it can be deleted, and the lookup is itself an assertion the
 * test is making ("a project by this name now exists") — so a registry of ids
 * could only be filled after that assertion passed, which is exactly the path a
 * failing test does not take. A name is knowable before the export is even sent,
 * so the test registers it first and the registry drains whatever really exists
 * at teardown.
 *
 * Why not lean on the run-prefix sweep in `global-teardown.ts`: it does cover
 * projects, but it fires once at the end of the whole run. These specs assert
 * project POPULATIONS by prefix ("exactly one project exists for the NFC/NFD
 * pair"), so a project left behind by an earlier failure is not inert — it is a
 * false result for the next spec that counts.
 *
 * Deleting a project takes its traces and spans with it, which is every entity
 * these specs create.
 */
export const test = baseTest.extend<OtelRoutedProjectsFixtures>({
  registerProjectCleanup: async ({ backendClient }, use, testInfo) => {
    const registry: string[] = [];
    await use((name) => {
      registry.push(name);
    });
    if (shouldLeaveArtifacts(testInfo)) return;
    for (const name of registry) {
      try {
        // Exact match, not the prefix search's startsWith: a registered name is
        // a prefix of the longer names in the same test (the 150/151-code-point
        // pair share a stem), and deleting by prefix would take a project a
        // later assertion in another worker still needs.
        for (const project of await backendClient.listProjectsWithPrefix(name)) {
          if (project.name === name) await backendClient.deleteProject(project.id);
        }
      } catch (err) {
        console.warn(`[registerProjectCleanup] delete warning for project ${name}:`, err);
      }
    }
  },
});

export { expect } from './sme-internal-thread-queue.fixture';
