import { Opik } from 'opik';
import { test as baseTest } from './second-project.fixture';
import { shouldLeaveArtifacts } from '../core/artifacts';

/** One project, and the TypeScript SDK client bound to it. */
export interface ScopedTsClient {
  /** The project this client's calls should be confined to. */
  projectId: string;
  projectName: string;
  client: Opik;
  /** The prompt this client created in its own project. */
  promptName: string;
  /** The dataset this client created in its own project. */
  datasetName: string;
}

export interface ProjectScopedTsClientsFixtures {
  /**
   * Two TypeScript SDK clients, each bound to a different project, each holding
   * one prompt and one dataset of its own.
   *
   * The shape a by-name lookup's project scoping needs and one client cannot
   * give: "this call returned my prompt" is satisfied by a call that returned
   * every prompt in the workspace, so the only way to see the difference is to
   * have a second project whose entity must NOT come back.
   *
   * Separate from `sdkClient.typescript`, which is the suite's shared
   * workspace-level client: `projectName` is fixed at construction on
   * `OpikConfig`, so a client bound to a project is a different client, not a
   * different call. Two are built here rather than in the test because the
   * entities have to exist before either lookup runs, and because their
   * teardown is the fixture's job.
   */
  projectScopedTsClients: { a: ScopedTsClient; b: ScopedTsClient };
}

export const test = baseTest.extend<ProjectScopedTsClientsFixtures>({
  projectScopedTsClients: async (
    { envConfig, backendClient, project, secondProject, testNamespace },
    use,
    testInfo,
  ) => {
    if (envConfig.deployment !== 'oss' && !envConfig.apiKey) {
      throw new Error(
        'projectScopedTsClients requires envConfig.apiKey on a non-oss deployment — ' +
          'the same requirement makeTypescriptSdk states',
      );
    }

    const build = async (
      label: 'a' | 'b',
      scope: { id: string; name: string },
    ): Promise<ScopedTsClient> => {
      const client = new Opik({
        apiKey: envConfig.apiKey ?? undefined,
        workspaceName: envConfig.workspace,
        apiUrl: envConfig.apiBaseUrl,
        // The whole point of this fixture: the client's own project is what an
        // unscoped by-name lookup is supposed to fall back to.
        projectName: scope.name,
      });
      const promptName = `${testNamespace}-${label}-prompt`;
      const datasetName = `${testNamespace}-${label}-ds`;
      await client.createPrompt({ name: promptName, prompt: `${label} template {{x}}` });
      await client.createDataset(datasetName, `${label} dataset`);
      // The dataset create is queued, and `getDatasets` flushes the queue before
      // it lists — but a lookup from the OTHER client would not, so the seed has
      // to be settled before any test reads it.
      await client.flush();
      return {
        projectId: scope.id,
        projectName: scope.name,
        client,
        promptName,
        datasetName,
      };
    };

    const a = await build('a', project);
    const b = await build('b', secondProject);

    await testInfo.attach('opik.project-scoped-ts-clients', {
      body: JSON.stringify(
        {
          a: { project: a.projectName, prompt: a.promptName, dataset: a.datasetName },
          b: { project: b.projectName, prompt: b.promptName, dataset: b.datasetName },
        },
        null,
        2,
      ),
      contentType: 'application/json',
    });

    await use({ a, b });

    if (shouldLeaveArtifacts(testInfo)) return;

    for (const scoped of [a, b]) {
      // Prompts do not cascade with their project, and the run-prefix sweep in
      // global-teardown does know about prompts — but by prefix only at the end
      // of the run, which would leave one test's prompts visible to the next
      // one's searchPrompts. Deleted here, by the id the DELETE endpoint wants:
      // resolved by name rather than taken off the create, because a create
      // hands back a prompt VERSION id and
      // `DELETE /v1/private/prompts/{version id}` 404s behind a catch.
      try {
        const promptId = await backendClient.findPromptIdByName(scoped.promptName, scoped.projectId);
        if (promptId !== null) await backendClient.deletePrompt(promptId);
      } catch (err) {
        console.warn(
          `[projectScopedTsClients] prompt delete warning for ${scoped.promptName}:`,
          err,
        );
      }
      try {
        const dataset = await backendClient.findDatasetByName(
          scoped.datasetName,
          scoped.projectName,
        );
        if (dataset !== null) await backendClient.deleteDataset(dataset.id);
      } catch (err) {
        console.warn(
          `[projectScopedTsClients] dataset delete warning for ${scoped.datasetName}:`,
          err,
        );
      }
    }
  },
});

export { expect } from './second-project.fixture';
