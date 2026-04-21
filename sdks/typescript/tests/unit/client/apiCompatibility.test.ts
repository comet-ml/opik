/**
 * API Backward Compatibility Tests
 *
 * Verifies that all SDK methods continue to work correctly when users
 * do NOT specify projectName anywhere — neither in the client constructor
 * nor in individual method calls.
 *
 * This is the "old API" contract: users who have never heard of projectName
 * should experience zero regressions after the projectName feature was added.
 *
 * Each test exercises the full method flow (not just checking a single assertion)
 * to ensure no intermediate step breaks when projectName is absent.
 */
import { Opik } from "opik";
import { Dataset } from "@/dataset/Dataset";
import { Experiment } from "@/experiment/Experiment";
import { Prompt } from "@/prompt";
import { ChatPrompt } from "@/prompt/ChatPrompt";
import { OpikApiError } from "@/rest_api";
import {
  mockAPIFunction,
  createMockHttpResponsePromise,
  mockAPIFunctionWithStream,
} from "../../mockUtils";

describe("API backward compatibility — no projectName specified anywhere", () => {
  let client: Opik;

  beforeEach(() => {
    // Initialize client exactly as a user who never heard of projectName would
    client = new Opik();
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe("Dataset operations without projectName", () => {
    beforeEach(() => {
      vi.spyOn(
        client.api.datasets,
        "getDatasetByIdentifier"
      ).mockImplementation(() =>
        createMockHttpResponsePromise({
          id: "ds-id-123",
          name: "my-dataset",
          description: "A test dataset",
        })
      );
      vi.spyOn(client.api.datasets, "findDatasets").mockImplementation(() =>
        createMockHttpResponsePromise({
          content: [
            { id: "ds-id-123", name: "my-dataset" },
            { id: "ds-id-456", name: "another-dataset" },
          ],
        })
      );
      vi.spyOn(client.api.projects, "retrieveProject").mockImplementation(() =>
        createMockHttpResponsePromise({
          id: "default-project-id",
          name: "Default Project",
        })
      );
      vi.spyOn(client.api.datasets, "deleteDatasetsBatch").mockImplementation(
        mockAPIFunction
      );
      vi.spyOn(client.datasetBatchQueue, "create");
      vi.spyOn(client.datasetBatchQueue, "flush").mockResolvedValue(undefined);
    });

    it("getDataset(name) works without projectName", async () => {
      const dataset = await client.getDataset("my-dataset");

      expect(dataset).toBeInstanceOf(Dataset);
      expect(dataset.name).toBe("my-dataset");
      expect(dataset.id).toBe("ds-id-123");
    });

    it("createDataset(name) works with only name", async () => {
      const dataset = await client.createDataset("my-dataset");

      expect(dataset).toBeInstanceOf(Dataset);
      expect(dataset.name).toBe("my-dataset");
      expect(client.datasetBatchQueue.create).toHaveBeenCalled();
    });

    it("createDataset(name, description) works without projectName", async () => {
      const dataset = await client.createDataset(
        "my-dataset",
        "Some description"
      );

      expect(dataset).toBeInstanceOf(Dataset);
      expect(dataset.name).toBe("my-dataset");
    });

    it("getOrCreateDataset(name) works without projectName", async () => {
      const dataset = await client.getOrCreateDataset("my-dataset");

      expect(dataset).toBeInstanceOf(Dataset);
      expect(dataset.name).toBe("my-dataset");
    });

    it("getDatasets() works without any arguments", async () => {
      const datasets = await client.getDatasets();

      expect(datasets).toHaveLength(2);
      expect(datasets[0]).toBeInstanceOf(Dataset);
      expect(datasets[0].name).toBe("my-dataset");
    });

    it("deleteDataset(name) works without projectName", async () => {
      await client.deleteDataset("my-dataset");

      expect(
        client.api.datasets.getDatasetByIdentifier
      ).toHaveBeenCalledWith(
        expect.objectContaining({ datasetName: "my-dataset" })
      );
    });
  });

  describe("Experiment operations without projectName", () => {
    beforeEach(() => {
      vi.spyOn(
        client.api.experiments,
        "createExperiment"
      ).mockImplementation(mockAPIFunction);
      vi.spyOn(
        client.api.experiments,
        "streamExperiments"
      ).mockImplementation(() =>
        mockAPIFunctionWithStream(
          JSON.stringify({
            id: "exp-id-123",
            dataset_id: "ds-id",
            dataset_name: "ds-name",
            name: "my-experiment",
          }) + "\n"
        )
      );
      vi.spyOn(
        client.api.datasets,
        "getDatasetByIdentifier"
      ).mockImplementation(() =>
        createMockHttpResponsePromise({
          id: "ds-id",
          name: "ds-name",
        })
      );
    });

    it("createExperiment works with only name and datasetName", async () => {
      const experiment = await client.createExperiment({
        name: "my-experiment",
        datasetName: "ds-name",
      });

      expect(experiment).toBeInstanceOf(Experiment);
      expect(experiment.name).toBe("my-experiment");
      expect(experiment.id).toBeDefined();
    });

    it("createExperiment works with experimentConfig but no projectName", async () => {
      const experiment = await client.createExperiment({
        name: "my-experiment",
        datasetName: "ds-name",
        experimentConfig: { model: "gpt-4", temperature: 0.7 },
      });

      expect(experiment).toBeInstanceOf(Experiment);
      expect(experiment.name).toBe("my-experiment");
    });

    it("getExperimentsByName(name) works without projectName", async () => {
      const experiments = await client.getExperimentsByName("my-experiment");

      expect(experiments).toHaveLength(1);
      expect(experiments[0]).toBeInstanceOf(Experiment);
      expect(experiments[0].name).toBe("my-experiment");
    });

    it("getExperiment(name) works without projectName", async () => {
      const experiment = await client.getExperiment("my-experiment");

      expect(experiment).toBeInstanceOf(Experiment);
      expect(experiment.name).toBe("my-experiment");
    });

    it("getDatasetExperiments(datasetName) works without projectName", async () => {
      vi.spyOn(
        client.api.experiments,
        "findExperiments"
      ).mockImplementation(() =>
        createMockHttpResponsePromise({
          content: [
            {
              id: "exp-id-123",
              datasetId: "ds-id",
              datasetName: "ds-name",
              name: "my-experiment",
            },
          ],
          total: 1,
        })
      );

      const experiments = await client.getDatasetExperiments("ds-name");

      expect(experiments.length).toBeGreaterThanOrEqual(1);
      expect(experiments[0]).toBeInstanceOf(Experiment);
    });
  });

  describe("Prompt operations without projectName", () => {
    beforeEach(() => {
      // fetchLatestPromptVersion — 404 means new prompt
      vi.spyOn(
        client.api.prompts,
        "retrievePromptVersion"
      ).mockImplementation(() => {
        throw new OpikApiError({ message: "Not found", statusCode: 404 });
      });
      vi.spyOn(
        client.api.prompts,
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
      vi.spyOn(client.api.prompts, "getPromptById").mockImplementation(() =>
        createMockHttpResponsePromise({
          id: "prompt-id",
          name: "my-prompt",
        })
      );
      vi.spyOn(client.api.prompts, "getPrompts").mockImplementation(() =>
        createMockHttpResponsePromise({
          content: [{ id: "prompt-id", name: "my-prompt" }],
        })
      );
    });

    it("createPrompt works with only name and prompt template", async () => {
      const prompt = await client.createPrompt({
        name: "my-prompt",
        prompt: "Hello {{name}}!",
      });

      expect(prompt).toBeInstanceOf(Prompt);
      expect(prompt.name).toBe("my-prompt");
    });

    it("createChatPrompt works with only name and messages", async () => {
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

      const chatPrompt = await client.createChatPrompt({
        name: "my-chat-prompt",
        messages: [{ role: "user", content: "Hello" }],
      });

      expect(chatPrompt).toBeInstanceOf(ChatPrompt);
      expect(chatPrompt.name).toBe("my-prompt");
    });

    it("getPrompt works with only name", async () => {
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

      const prompt = await client.getPrompt({ name: "my-prompt" });

      expect(prompt).toBeInstanceOf(Prompt);
      expect(prompt!.name).toBe("my-prompt");
    });

    it("getChatPrompt works with only name", async () => {
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

      const chatPrompt = await client.getChatPrompt({ name: "my-prompt" });

      expect(chatPrompt).toBeInstanceOf(ChatPrompt);
      expect(chatPrompt!.name).toBe("my-prompt");
    });
  });

  describe("Default projectName is used transparently", () => {
    it("resolves to 'Default Project' when nothing is configured", () => {
      expect(client.config.projectName).toBe("Default Project");
    });

    it("passes default projectName to API calls without user needing to know", async () => {
      vi.spyOn(
        client.api.datasets,
        "getDatasetByIdentifier"
      ).mockImplementation(mockAPIFunction);

      await client.getDataset("my-dataset");

      // The API call should include projectName transparently
      expect(
        client.api.datasets.getDatasetByIdentifier
      ).toHaveBeenCalledWith(
        expect.objectContaining({ projectName: "Default Project" })
      );
    });

    it("OPIK_PROJECT_NAME env var overrides default without code changes", () => {
      const originalEnv = process.env.OPIK_PROJECT_NAME;
      try {
        process.env.OPIK_PROJECT_NAME = "my-env-project";
        const envClient = new Opik();
        expect(envClient.config.projectName).toBe("my-env-project");
      } finally {
        if (originalEnv === undefined) {
          delete process.env.OPIK_PROJECT_NAME;
        } else {
          process.env.OPIK_PROJECT_NAME = originalEnv;
        }
      }
    });
  });
});
