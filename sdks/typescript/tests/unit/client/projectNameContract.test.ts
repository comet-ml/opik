/**
 * Unit tests for projectName parameter propagation and backward compatibility.
 *
 * These tests verify that:
 * 1. projectName flows correctly through all SDK methods to the REST API
 * 2. Explicit projectName overrides the client's default
 * 3. Methods work without projectName (backward compatibility)
 * 4. The resolveProjectName pattern works correctly
 *
 * Equivalent to Python SDK's test_experiment_item_project_name.py
 * and related projectName contract tests.
 */
import { Opik } from "opik";
import { MockInstance } from "vitest";
import { OpikApiError } from "@/rest_api";
import {
  mockAPIFunction,
  createMockHttpResponsePromise,
  mockAPIFunctionWithStream,
} from "../../mockUtils";

function setupPromptMocks(targetClient: Opik) {
  // fetchLatestPromptVersion calls retrievePromptVersion — return 404 (new prompt)
  vi.spyOn(
    targetClient.api.prompts,
    "retrievePromptVersion"
  ).mockImplementation(() => {
    throw new OpikApiError({ message: "Not found", statusCode: 404 });
  });

  // createPromptVersion returns full version detail
  vi.spyOn(
    targetClient.api.prompts,
    "createPromptVersion"
  ).mockImplementation(() =>
    createMockHttpResponsePromise({
      id: "version-id",
      promptId: "prompt-id",
      commit: "abc123",
      template: "Hello {{name}}",
      type: "mustache",
    })
  );

  // getPromptById returns prompt data (called after createPromptVersion)
  vi.spyOn(targetClient.api.prompts, "getPromptById").mockImplementation(() =>
    createMockHttpResponsePromise({
      id: "prompt-id",
      name: "prompt-name",
    })
  );

  // getPrompts returns prompt search results (called by getPrompt/getChatPrompt)
  vi.spyOn(targetClient.api.prompts, "getPrompts").mockImplementation(() =>
    createMockHttpResponsePromise({
      content: [{ id: "prompt-id", name: "prompt-name" }],
    })
  );
}

describe("projectName contract tests", () => {
  const DEFAULT_PROJECT = "my-default-project";
  const EXPLICIT_PROJECT = "my-explicit-project";

  let client: Opik;

  beforeEach(() => {
    client = new Opik({ projectName: DEFAULT_PROJECT });

    // Mock dataset API methods
    vi.spyOn(client.api.datasets, "getDatasetByIdentifier").mockImplementation(
      mockAPIFunction
    );
    vi.spyOn(client.api.datasets, "findDatasets").mockImplementation(() =>
      createMockHttpResponsePromise({ content: [] })
    );
    vi.spyOn(client.api.projects, "retrieveProject").mockImplementation(() =>
      createMockHttpResponsePromise({
        id: "project-id-123",
        name: DEFAULT_PROJECT,
      })
    );
    vi.spyOn(client.datasetBatchQueue, "create");
    vi.spyOn(client.datasetBatchQueue, "flush").mockResolvedValue(undefined);

    // Mock prompt API methods
    setupPromptMocks(client);
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe("resolveProjectName: default vs explicit", () => {
    it("should use client default projectName when none is provided", async () => {
      await client.getDataset("test-dataset");

      expect(client.api.datasets.getDatasetByIdentifier).toHaveBeenCalledWith(
        expect.objectContaining({ projectName: DEFAULT_PROJECT })
      );
    });

    it("should use explicit projectName when provided", async () => {
      await client.getDataset("test-dataset", EXPLICIT_PROJECT);

      expect(client.api.datasets.getDatasetByIdentifier).toHaveBeenCalledWith(
        expect.objectContaining({ projectName: EXPLICIT_PROJECT })
      );
    });
  });

  describe("Dataset methods pass projectName to API", () => {
    it("getDataset passes projectName to getDatasetByIdentifier", async () => {
      await client.getDataset("ds-name", EXPLICIT_PROJECT);

      expect(client.api.datasets.getDatasetByIdentifier).toHaveBeenCalledWith(
        expect.objectContaining({
          datasetName: "ds-name",
          projectName: EXPLICIT_PROJECT,
        })
      );
    });

    it("getDataset returns Dataset with projectName set", async () => {
      vi.spyOn(
        client.api.datasets,
        "getDatasetByIdentifier"
      ).mockImplementation(() =>
        createMockHttpResponsePromise({
          id: "ds-id",
          name: "ds-name",
        })
      );

      const dataset = await client.getDataset("ds-name", EXPLICIT_PROJECT);
      expect(dataset.projectName).toBe(EXPLICIT_PROJECT);
    });

    it("createDataset passes projectName to batch queue", async () => {
      await client.createDataset("ds-name", "description", EXPLICIT_PROJECT);

      expect(client.datasetBatchQueue.create).toHaveBeenCalledWith(
        expect.objectContaining({ projectName: EXPLICIT_PROJECT })
      );
    });

    it("createDataset returns Dataset with projectName set", async () => {
      const dataset = await client.createDataset(
        "ds-name",
        "desc",
        EXPLICIT_PROJECT
      );
      expect(dataset.projectName).toBe(EXPLICIT_PROJECT);
    });

    it("getOrCreateDataset passes projectName through", async () => {
      vi.spyOn(
        client.api.datasets,
        "getDatasetByIdentifier"
      ).mockImplementation(() =>
        createMockHttpResponsePromise({
          id: "ds-id",
          name: "ds-name",
        })
      );

      const dataset = await client.getOrCreateDataset(
        "ds-name",
        "desc",
        EXPLICIT_PROJECT
      );

      expect(client.api.datasets.getDatasetByIdentifier).toHaveBeenCalledWith(
        expect.objectContaining({ projectName: EXPLICIT_PROJECT })
      );
      expect(dataset.projectName).toBe(EXPLICIT_PROJECT);
    });

    it("getDatasets resolves projectId from projectName", async () => {
      await client.getDatasets();

      expect(client.api.projects.retrieveProject).toHaveBeenCalledWith({
        name: DEFAULT_PROJECT,
      });
      expect(client.api.datasets.findDatasets).toHaveBeenCalledWith(
        expect.objectContaining({ projectId: "project-id-123" })
      );
    });

    it("getDatasets uses explicit projectName to resolve projectId", async () => {
      vi.spyOn(client.api.projects, "retrieveProject").mockImplementation(() =>
        createMockHttpResponsePromise({
          id: "explicit-project-id",
          name: EXPLICIT_PROJECT,
        })
      );

      await client.getDatasets(undefined, EXPLICIT_PROJECT);

      expect(client.api.projects.retrieveProject).toHaveBeenCalledWith({
        name: EXPLICIT_PROJECT,
      });
      expect(client.api.datasets.findDatasets).toHaveBeenCalledWith(
        expect.objectContaining({ projectId: "explicit-project-id" })
      );
    });
  });

  describe("Dataset item insert passes projectName to API", () => {
    function setupInsertMocks() {
      vi.spyOn(
        client.api.datasets,
        "getDatasetByIdentifier"
      ).mockImplementation(() =>
        createMockHttpResponsePromise({
          id: "ds-id",
          name: "ds-name",
        })
      );

      vi.spyOn(
        client.api.datasets,
        "createOrUpdateDatasetItems"
      ).mockImplementation(() => createMockHttpResponsePromise(undefined));

      // syncHashes calls getDatasetItems which uses streamDatasetItems
      const emptyStream = new ReadableStream<Uint8Array>({
        start(controller) { controller.close(); },
      });
      vi.spyOn(
        client.api.datasets,
        "streamDatasetItems"
      ).mockImplementation(() =>
        createMockHttpResponsePromise(emptyStream)
      );
    }

    it("insert passes projectName to createOrUpdateDatasetItems", async () => {
      setupInsertMocks();

      const dataset = await client.getDataset("ds-name", EXPLICIT_PROJECT);
      await dataset.insert([{ input: "hello", output: "world" }]);

      expect(
        client.api.datasets.createOrUpdateDatasetItems
      ).toHaveBeenCalledWith(
        expect.objectContaining({ projectName: EXPLICIT_PROJECT })
      );
    });

    it("insert uses client default projectName when dataset has no explicit project", async () => {
      setupInsertMocks();

      const dataset = await client.getDataset("ds-name");
      await dataset.insert([{ input: "hello", output: "world" }]);

      expect(
        client.api.datasets.createOrUpdateDatasetItems
      ).toHaveBeenCalledWith(
        expect.objectContaining({ projectName: DEFAULT_PROJECT })
      );
    });
  });

  describe("Experiment methods pass projectName to API", () => {
    let streamExperimentsSpy: MockInstance;

    beforeEach(() => {
      streamExperimentsSpy = vi
        .spyOn(client.api.experiments, "streamExperiments")
        .mockImplementation(() =>
          mockAPIFunctionWithStream(
            JSON.stringify({
              id: "exp-id",
              dataset_id: "ds-id",
              dataset_name: "ds-name",
              name: "exp-name",
            }) + "\n"
          )
        );
      vi.spyOn(
        client.api.experiments,
        "createExperiment"
      ).mockImplementation(mockAPIFunction);
    });

    it("createExperiment passes resolved projectName to API", async () => {
      await client.createExperiment({
        name: "exp-name",
        datasetName: "ds-name",
        projectName: EXPLICIT_PROJECT,
      });

      expect(client.api.experiments.createExperiment).toHaveBeenCalledWith(
        expect.objectContaining({ projectName: EXPLICIT_PROJECT })
      );
    });

    it("createExperiment uses default projectName when not specified", async () => {
      await client.createExperiment({
        name: "exp-name",
        datasetName: "ds-name",
      });

      expect(client.api.experiments.createExperiment).toHaveBeenCalledWith(
        expect.objectContaining({ projectName: DEFAULT_PROJECT })
      );
    });

    it("createExperiment returns Experiment with projectName set", async () => {
      const experiment = await client.createExperiment({
        name: "exp-name",
        datasetName: "ds-name",
        projectName: EXPLICIT_PROJECT,
      });

      expect(experiment.projectName).toBe(EXPLICIT_PROJECT);
    });

    it("getExperimentsByName passes projectName to streamExperiments", async () => {
      await client.getExperimentsByName("exp-name", EXPLICIT_PROJECT);

      expect(streamExperimentsSpy).toHaveBeenCalledWith(
        expect.objectContaining({ projectName: EXPLICIT_PROJECT })
      );
    });

    it("getExperimentsByName uses default projectName when not specified", async () => {
      await client.getExperimentsByName("exp-name");

      expect(streamExperimentsSpy).toHaveBeenCalledWith(
        expect.objectContaining({ projectName: DEFAULT_PROJECT })
      );
    });
  });

  describe("Prompt methods pass projectName to API", () => {
    it("createPrompt passes resolved projectName to createPromptVersion", async () => {
      await client.createPrompt({
        name: "prompt-name",
        prompt: "Hello {{name}}",
        projectName: EXPLICIT_PROJECT,
      });

      expect(client.api.prompts.createPromptVersion).toHaveBeenCalledWith(
        expect.objectContaining({ projectName: EXPLICIT_PROJECT }),
        expect.anything()
      );
    });

    it("createPrompt uses default projectName when not specified", async () => {
      await client.createPrompt({
        name: "prompt-name",
        prompt: "Hello {{name}}",
      });

      expect(client.api.prompts.createPromptVersion).toHaveBeenCalledWith(
        expect.objectContaining({ projectName: DEFAULT_PROJECT }),
        expect.anything()
      );
    });

    it("createChatPrompt passes resolved projectName to createPromptVersion", async () => {
      // For chat prompt creation, the mock must return a chat-compatible version
      vi.spyOn(
        client.api.prompts,
        "createPromptVersion"
      ).mockImplementation(() =>
        createMockHttpResponsePromise({
          id: "version-id",
          promptId: "prompt-id",
          commit: "abc123",
          template: '[{"role":"user","content":"Hello"}]',
          type: "mustache",
        })
      );

      await client.createChatPrompt({
        name: "chat-prompt",
        messages: [{ role: "user", content: "Hello" }],
        projectName: EXPLICIT_PROJECT,
      });

      expect(client.api.prompts.createPromptVersion).toHaveBeenCalledWith(
        expect.objectContaining({ projectName: EXPLICIT_PROJECT }),
        expect.anything()
      );
    });

    it("getPrompt passes resolved projectName to retrievePromptVersion", async () => {
      // For getPrompt, retrievePromptVersion must succeed with "text" structure
      vi.spyOn(
        client.api.prompts,
        "retrievePromptVersion"
      ).mockImplementation(() =>
        createMockHttpResponsePromise({
          id: "version-id",
          promptId: "prompt-id",
          commit: "abc123",
          template: "Hello {{name}}",
          templateStructure: "text",
        })
      );

      await client.getPrompt({
        name: "prompt-name",
        projectName: EXPLICIT_PROJECT,
      });

      expect(client.api.prompts.retrievePromptVersion).toHaveBeenCalledWith(
        expect.objectContaining({ projectName: EXPLICIT_PROJECT }),
        expect.anything()
      );
    });

    it("getChatPrompt passes resolved projectName to retrievePromptVersion", async () => {
      // For getChatPrompt, retrievePromptVersion must return "chat" structure
      vi.spyOn(
        client.api.prompts,
        "retrievePromptVersion"
      ).mockImplementation(() =>
        createMockHttpResponsePromise({
          id: "version-id",
          promptId: "prompt-id",
          commit: "abc123",
          template: '[{"role":"system","content":"Hello"}]',
          templateStructure: "chat",
        })
      );

      await client.getChatPrompt({
        name: "chat-prompt",
        projectName: EXPLICIT_PROJECT,
      });

      expect(client.api.prompts.retrievePromptVersion).toHaveBeenCalledWith(
        expect.objectContaining({ projectName: EXPLICIT_PROJECT }),
        expect.anything()
      );
    });
  });

  describe("Backward compatibility: no projectName configured", () => {
    let clientNoProject: Opik;

    beforeEach(() => {
      clientNoProject = new Opik();

      vi.spyOn(
        clientNoProject.api.datasets,
        "getDatasetByIdentifier"
      ).mockImplementation(() =>
        createMockHttpResponsePromise({ id: "ds-id", name: "ds-name" })
      );
      vi.spyOn(clientNoProject.api.projects, "retrieveProject").mockImplementation(
        () =>
          createMockHttpResponsePromise({
            id: "default-project-id",
            name: "Default Project",
          })
      );
      vi.spyOn(
        clientNoProject.api.datasets,
        "findDatasets"
      ).mockImplementation(() =>
        createMockHttpResponsePromise({ content: [] })
      );
      vi.spyOn(clientNoProject.datasetBatchQueue, "create");
      vi.spyOn(
        clientNoProject.datasetBatchQueue,
        "flush"
      ).mockResolvedValue(undefined);
      vi.spyOn(
        clientNoProject.api.experiments,
        "createExperiment"
      ).mockImplementation(mockAPIFunction);

      setupPromptMocks(clientNoProject);
    });

    it("getDataset still works and uses config default projectName", async () => {
      const dataset = await clientNoProject.getDataset("ds-name");

      expect(
        clientNoProject.api.datasets.getDatasetByIdentifier
      ).toHaveBeenCalledWith(
        expect.objectContaining({
          projectName: clientNoProject.config.projectName,
        })
      );
      expect(dataset.name).toBe("ds-name");
    });

    it("createDataset still works and uses config default projectName", async () => {
      const dataset = await clientNoProject.createDataset("ds-name");

      expect(clientNoProject.datasetBatchQueue.create).toHaveBeenCalledWith(
        expect.objectContaining({
          projectName: clientNoProject.config.projectName,
        })
      );
      expect(dataset.projectName).toBe(clientNoProject.config.projectName);
    });

    it("createExperiment still works with config default projectName", async () => {
      await clientNoProject.createExperiment({
        name: "exp-name",
        datasetName: "ds-name",
      });

      expect(
        clientNoProject.api.experiments.createExperiment
      ).toHaveBeenCalledWith(
        expect.objectContaining({
          projectName: clientNoProject.config.projectName,
        })
      );
    });

    it("createPrompt still works with config default projectName", async () => {
      await clientNoProject.createPrompt({
        name: "prompt-name",
        prompt: "Hello {{name}}",
      });

      expect(
        clientNoProject.api.prompts.createPromptVersion
      ).toHaveBeenCalledWith(
        expect.objectContaining({
          projectName: clientNoProject.config.projectName,
        }),
        expect.anything()
      );
    });
  });
});
