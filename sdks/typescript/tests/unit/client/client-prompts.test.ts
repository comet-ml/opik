import { Opik } from "opik";
import { MockInstance } from "vitest";
import { Prompt, PromptType } from "@/prompt";
import {
  EnvironmentNotFoundError,
  PromptNotFoundError,
  PromptVersionNotAssignableToEnvironmentError,
} from "@/prompt/errors";
import { OpikApiError } from "@/rest_api";
import { logger } from "@/utils/logger";
import {
  mockAPIFunction,
  createMockHttpResponsePromise,
} from "../../mockUtils";
import * as OpikApi from "@/rest_api/api";
import { PromptTemplateStructure } from "@/prompt/types";
import { getGlobalCache } from "@/prompt/promptCache";
import { trackStorage } from "@/decorators/track";
import { Trace } from "@/tracer/Trace";
import { Span } from "@/tracer/Span";
import type { PromptInfoDict } from "@/tracer/types";

describe("Opik prompt operations", () => {
  let client: Opik;
  let retrievePromptVersionSpy: MockInstance<
    typeof client.api.prompts.retrievePromptVersion
  >;
  let getPromptsSpy: MockInstance<typeof client.api.prompts.getPrompts>;
  let getPromptByIdSpy: MockInstance<typeof client.api.prompts.getPromptById>;
  let createPromptVersionSpy: MockInstance<
    typeof client.api.prompts.createPromptVersion
  >;
  let deletePromptsBatchSpy: MockInstance<
    typeof client.api.prompts.deletePromptsBatch
  >;
  let retrieveProjectSpy: MockInstance<
    typeof client.api.projects.retrieveProject
  >;
  let loggerErrorSpy: MockInstance<typeof logger.error>;
  let loggerInfoSpy: MockInstance<typeof logger.info>;
  let loggerDebugSpy: MockInstance<typeof logger.debug>;

  beforeEach(() => {
    getGlobalCache().clear();

    client = new Opik({
      projectName: "opik-sdk-typescript",
    });

    // Mock API methods
    retrieveProjectSpy = vi
      .spyOn(client.api.projects, "retrieveProject")
      .mockRejectedValue(new Error("Project not found"));

    retrievePromptVersionSpy = vi
      .spyOn(client.api.prompts, "retrievePromptVersion")
      .mockImplementation(mockAPIFunction);

    getPromptsSpy = vi
      .spyOn(client.api.prompts, "getPrompts")
      .mockImplementation(mockAPIFunction);

    getPromptByIdSpy = vi
      .spyOn(client.api.prompts, "getPromptById")
      .mockImplementation(mockAPIFunction);

    createPromptVersionSpy = vi
      .spyOn(client.api.prompts, "createPromptVersion")
      .mockImplementation(mockAPIFunction);

    deletePromptsBatchSpy = vi
      .spyOn(client.api.prompts, "deletePromptsBatch")
      .mockImplementation(mockAPIFunction);

    // Mock logger methods
    loggerErrorSpy = vi.spyOn(logger, "error");
    loggerInfoSpy = vi.spyOn(logger, "info");
    loggerDebugSpy = vi.spyOn(logger, "debug");
  });

  afterEach(() => {
    retrieveProjectSpy.mockRestore();
    retrievePromptVersionSpy.mockRestore();
    getPromptsSpy.mockRestore();
    getPromptByIdSpy.mockRestore();
    createPromptVersionSpy.mockRestore();
    deletePromptsBatchSpy.mockRestore();
    loggerErrorSpy.mockRestore();
    loggerInfoSpy.mockRestore();
    loggerDebugSpy.mockRestore();
  });

  describe("createPrompt", () => {
    it("should create a new prompt successfully", async () => {
      const mockVersionDetail: OpikApi.PromptVersionDetail = {
        id: "version-id",
        promptId: "prompt-id",
        commit: "abc123de",
        template: "Hello {{name}}!",
        type: "mustache",
        createdAt: new Date("2024-01-01"),
      };

      const mockPromptData: OpikApi.PromptPublic = {
        id: "prompt-id",
        name: "test-prompt",
        description: "Test description",
      };

      // First call: check if prompt exists (returns null for new prompt)
      retrievePromptVersionSpy.mockImplementationOnce(() => {
        throw new OpikApiError({
          message: "Not found",
          statusCode: 404,
        });
      });

      // Second call: create new version
      createPromptVersionSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockVersionDetail)
      );

      // Third call: get prompt data
      getPromptByIdSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptData)
      );

      const result = await client.createPrompt({
        name: "test-prompt",
        prompt: "Hello {{name}}!",
      });

      expect(result).toBeInstanceOf(Prompt);
      expect(result.name).toBe("test-prompt");
      expect(result.prompt).toBe("Hello {{name}}!");
      expect(createPromptVersionSpy).toHaveBeenCalledWith(
        {
          name: "test-prompt",
          version: {
            template: "Hello {{name}}!",
            metadata: undefined,
            type: PromptType.MUSTACHE,
          },
          templateStructure: PromptTemplateStructure.Text,
          projectName: "opik-sdk-typescript",
        },
        client.api.requestOptions
      );
      expect(loggerDebugSpy).toHaveBeenCalledWith("Creating prompt", {
        name: "test-prompt",
      });
    });

    it("should return existing version if identical (idempotent)", async () => {
      const mockVersionDetail: OpikApi.PromptVersionDetail = {
        id: "version-id",
        promptId: "prompt-id",
        commit: "abc123de",
        template: "Test {{variable}}",
        type: "mustache",
        createdAt: new Date("2024-01-01"),
      };

      const mockPromptData: OpikApi.PromptPublic = {
        id: "prompt-id",
        name: "existing-prompt",
      };

      // Latest version exists and matches
      retrievePromptVersionSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockVersionDetail)
      );

      getPromptByIdSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptData)
      );

      await client.createPrompt({
        name: "existing-prompt",
        prompt: "Test {{variable}}",
      });

      expect(createPromptVersionSpy).not.toHaveBeenCalled();
      expect(loggerDebugSpy).toHaveBeenCalledWith(
        "Returning existing prompt version",
        { name: "existing-prompt" }
      );
    });

    it("should create new version if template differs", async () => {
      const mockExistingVersion: OpikApi.PromptVersionDetail = {
        id: "version-id",
        promptId: "prompt-id",
        commit: "abc123de",
        template: "Old template",
        type: "mustache",
        createdAt: new Date("2024-01-01"),
      };

      const mockNewVersion: OpikApi.PromptVersionDetail = {
        id: "version-id-2",
        promptId: "prompt-id",
        commit: "def456gh",
        template: "New template",
        type: "mustache",
        createdAt: new Date("2024-01-02"),
      };

      const mockPromptData: OpikApi.PromptPublic = {
        id: "prompt-id",
        name: "test-prompt",
      };

      retrievePromptVersionSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockExistingVersion)
      );

      createPromptVersionSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockNewVersion)
      );

      getPromptByIdSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptData)
      );

      await client.createPrompt({
        name: "test-prompt",
        prompt: "New template",
      });

      expect(createPromptVersionSpy).toHaveBeenCalledWith(
        {
          name: "test-prompt",
          version: {
            template: "New template",
            metadata: undefined,
            type: PromptType.MUSTACHE,
          },
          templateStructure: PromptTemplateStructure.Text,
          projectName: "opik-sdk-typescript",
        },
        client.api.requestOptions
      );
    });

    it("should use default type MUSTACHE when not specified", async () => {
      const mockVersionDetail: OpikApi.PromptVersionDetail = {
        id: "version-id",
        promptId: "prompt-id",
        commit: "abc123de",
        template: "Test",
        type: "mustache",
        createdAt: new Date("2024-01-01"),
      };

      const mockPromptData: OpikApi.PromptPublic = {
        id: "prompt-id",
        name: "default-type-prompt",
      };

      retrievePromptVersionSpy.mockImplementationOnce(() => {
        throw new OpikApiError({
          message: "Not found",
          statusCode: 404,
        });
      });

      createPromptVersionSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockVersionDetail)
      );

      getPromptByIdSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptData)
      );

      await client.createPrompt({
        name: "default-type-prompt",
        prompt: "Test",
      });

      expect(createPromptVersionSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          version: expect.objectContaining({
            type: PromptType.MUSTACHE,
          }),
        }),
        client.api.requestOptions
      );
    });

    it("should create prompt with specified type", async () => {
      const mockVersionDetail: OpikApi.PromptVersionDetail = {
        id: "version-id",
        promptId: "prompt-id",
        commit: "abc123de",
        template: "Hello {{ name }}!",
        type: "jinja2",
        createdAt: new Date("2024-01-01"),
      };

      const mockPromptData: OpikApi.PromptPublic = {
        id: "prompt-id",
        name: "jinja2-prompt",
      };

      retrievePromptVersionSpy.mockImplementationOnce(() => {
        throw new OpikApiError({
          message: "Not found",
          statusCode: 404,
        });
      });

      createPromptVersionSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockVersionDetail)
      );

      getPromptByIdSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptData)
      );

      await client.createPrompt({
        name: "jinja2-prompt",
        prompt: "Hello {{ name }}!",
        type: PromptType.JINJA2,
      });

      expect(createPromptVersionSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          version: expect.objectContaining({
            type: PromptType.JINJA2,
          }),
        }),
        client.api.requestOptions
      );
    });

    it("should create prompt with metadata", async () => {
      const metadata = {
        version: "1.0",
        author: "test-user",
      };

      const mockVersionDetail: OpikApi.PromptVersionDetail = {
        id: "version-id",
        promptId: "prompt-id",
        commit: "abc123de",
        template: "Test",
        type: "mustache",
        createdAt: new Date("2024-01-01"),
      };

      const mockPromptData: OpikApi.PromptPublic = {
        id: "prompt-id",
        name: "prompt-with-metadata",
      };

      retrievePromptVersionSpy.mockImplementationOnce(() => {
        throw new OpikApiError({
          message: "Not found",
          statusCode: 404,
        });
      });

      createPromptVersionSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockVersionDetail)
      );

      getPromptByIdSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptData)
      );

      await client.createPrompt({
        name: "prompt-with-metadata",
        prompt: "Test",
        metadata,
      });

      expect(createPromptVersionSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          version: expect.objectContaining({
            metadata,
          }),
        }),
        client.api.requestOptions
      );
    });

    it("should return unsynced prompt on API error", async () => {
      const apiError = new OpikApiError({
        message: "Server error",
        statusCode: 500,
      });

      retrievePromptVersionSpy.mockImplementationOnce(() => {
        throw apiError;
      });

      const result = await client.createPrompt({
        name: "error-prompt",
        prompt: "Test",
      });

      expect(result).toBeInstanceOf(Prompt);
      expect(result.name).toBe("error-prompt");
      expect(result.prompt).toBe("Test");
      expect(result.synced).toBe(false);
      expect(result.id).toBeUndefined();
      expect(result.versionId).toBeUndefined();
      expect(result.commit).toBeUndefined();
    });

    it("should propagate non-API errors", async () => {
      retrievePromptVersionSpy.mockImplementationOnce(() => {
        throw new TypeError("unexpected error");
      });

      await expect(
        client.createPrompt({
          name: "error-prompt",
          prompt: "Test",
        })
      ).rejects.toThrow(TypeError);
    });
  });

  describe("getPrompt", () => {
    it("should retrieve prompt by name only (latest version)", async () => {
      const mockSearchResponse = {
        content: [
          {
            id: "prompt-id",
            name: "test-prompt",
            description: "Test description",
            tags: ["test"],
          },
        ],
      };

      const mockVersionDetail: OpikApi.PromptVersionDetail = {
        id: "version-id",
        promptId: "prompt-id",
        commit: "abc123de",
        template: "Hello {{name}}!",
        type: "mustache",
        createdAt: new Date("2024-01-01"),
        createdBy: "test-user",
      };

      getPromptsSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockSearchResponse)
      );

      retrievePromptVersionSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockVersionDetail)
      );

      const result = await client.getPrompt({ name: "test-prompt" });

      expect(getPromptsSpy).toHaveBeenCalledWith(
        {
          filters: JSON.stringify([
            { field: "name", operator: "=", value: "test-prompt" },
          ]),
          size: 1,
        },
        client.api.requestOptions
      );
      expect(retrievePromptVersionSpy).toHaveBeenCalledWith(
        { name: "test-prompt", projectName: "opik-sdk-typescript" },
        client.api.requestOptions
      );
      expect(result).toBeInstanceOf(Prompt);
      expect(result?.name).toBe("test-prompt");
      expect(result?.prompt).toBe("Hello {{name}}!");
      expect(loggerDebugSpy).toHaveBeenCalledWith("Getting prompt", {
        name: "test-prompt",
      });
    });

    it("should retrieve prompt by name and commit", async () => {
      const mockSearchResponse = {
        content: [
          {
            id: "prompt-id",
            name: "test-prompt",
          },
        ],
      };

      const mockVersionDetail: OpikApi.PromptVersionDetail = {
        id: "version-id",
        promptId: "prompt-id",
        commit: "specific-commit",
        template: "Specific version",
        type: "mustache",
        createdAt: new Date("2024-01-01"),
      };

      getPromptsSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockSearchResponse)
      );

      retrievePromptVersionSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockVersionDetail)
      );

      const result = await client.getPrompt({
        name: "test-prompt",
        commit: "specific-commit",
      });

      expect(retrievePromptVersionSpy).toHaveBeenCalledWith(
        { name: "test-prompt", commit: "specific-commit", projectName: "opik-sdk-typescript" },
        client.api.requestOptions
      );
      expect(result).toBeInstanceOf(Prompt);
      expect(result?.prompt).toBe("Specific version");
    });

    it("should reject when both commit and environment are provided", async () => {
      await expect(
        client.getPrompt({
          name: "test-prompt",
          commit: "abc12345",
          environment: "staging",
        })
      ).rejects.toThrow(/mutually exclusive/);

      expect(getPromptsSpy).not.toHaveBeenCalled();
      expect(retrievePromptVersionSpy).not.toHaveBeenCalled();
    });

    it("should return null when prompt doesn't exist", async () => {
      const mockSearchResponse = {
        content: [],
      };

      getPromptsSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockSearchResponse)
      );

      const result = await client.getPrompt({ name: "non-existent-prompt" });

      expect(result).toBeNull();
      expect(loggerDebugSpy).toHaveBeenCalledWith("Prompt not found", {
        name: "non-existent-prompt",
      });
    });

    it("should propagate API errors", async () => {
      const apiError = new OpikApiError({
        message: "Server error",
        statusCode: 500,
      });

      getPromptsSpy.mockImplementationOnce(() => {
        throw apiError;
      });

      await expect(client.getPrompt({ name: "test-prompt" })).rejects.toThrow();

      expect(loggerErrorSpy).toHaveBeenCalledWith(
        "Failed to get prompt",
        expect.objectContaining({
          error: apiError,
        })
      );
    });

    it("should handle network errors", async () => {
      const networkError = new Error("Network timeout");

      getPromptsSpy.mockImplementationOnce(() => {
        throw networkError;
      });

      await expect(client.getPrompt({ name: "test-prompt" })).rejects.toThrow(
        "Network timeout"
      );

      expect(loggerErrorSpy).toHaveBeenCalled();
    });

    it("should retrieve prompt by sequential version number", async () => {
      const mockSearchResponse = {
        content: [
          {
            id: "prompt-id",
            name: "test-prompt",
          },
        ],
      };

      const mockVersionDetail: OpikApi.PromptVersionDetail = {
        id: "version-id",
        promptId: "prompt-id",
        commit: "abc123de",
        versionNumber: "v3",
        template: "Version 3 content",
        type: "mustache",
        createdAt: new Date("2024-01-03"),
      };

      getPromptsSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockSearchResponse)
      );

      retrievePromptVersionSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockVersionDetail)
      );

      const result = await client.getPrompt({
        name: "test-prompt",
        version: "v3",
      });

      expect(retrievePromptVersionSpy).toHaveBeenCalledWith(
        {
          name: "test-prompt",
          projectName: "opik-sdk-typescript",
          versionNumber: "v3",
        },
        client.api.requestOptions
      );
      expect(result).toBeInstanceOf(Prompt);
      expect(result?.version).toBe("v3");
      expect(result?.commit).toBe("abc123de");
      expect(result?.prompt).toBe("Version 3 content");
    });

    it("should not leak the `version` SDK-only field into the REST request body", async () => {
      const mockSearchResponse = {
        content: [{ id: "prompt-id", name: "test-prompt" }],
      };
      const mockVersionDetail: OpikApi.PromptVersionDetail = {
        id: "version-id",
        promptId: "prompt-id",
        commit: "abc123de",
        versionNumber: "v1",
        template: "Hello",
        type: "mustache",
      };

      getPromptsSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockSearchResponse)
      );
      retrievePromptVersionSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockVersionDetail)
      );

      await client.getPrompt({ name: "test-prompt", version: "v1" });

      const callArgs = retrievePromptVersionSpy.mock.calls[0]?.[0] as unknown as Record<
        string,
        unknown
      >;
      expect(callArgs).toBeDefined();
      expect(callArgs.version).toBeUndefined();
      expect(callArgs.versionNumber).toBe("v1");
    });

    it("should reject without hitting the network when both commit and version are provided", async () => {
      await expect(
        client.getPrompt({
          name: "test-prompt",
          commit: "abc123de",
          version: "v3",
        })
      ).rejects.toThrow(/Provide either `commit` or `version`/);

      // Validation must short-circuit before any REST traffic
      expect(getPromptsSpy).not.toHaveBeenCalled();
      expect(retrievePromptVersionSpy).not.toHaveBeenCalled();
    });

    it("should populate prompt.version from the API response versionNumber field", async () => {
      const mockSearchResponse = {
        content: [{ id: "prompt-id", name: "test-prompt" }],
      };
      const mockVersionDetail: OpikApi.PromptVersionDetail = {
        id: "version-id",
        promptId: "prompt-id",
        commit: "abc123de",
        versionNumber: "v7",
        template: "Hi",
        type: "mustache",
      };

      getPromptsSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockSearchResponse)
      );
      retrievePromptVersionSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockVersionDetail)
      );

      const result = await client.getPrompt({ name: "test-prompt" });
      expect(result?.version).toBe("v7");
    });
  });

  describe("searchPrompts", () => {
    it("should search all prompts without filters", async () => {
      const mockPromptsResponse = {
        content: [
          {
            id: "prompt-1",
            name: "prompt-one",
            description: "First prompt",
          },
          {
            id: "prompt-2",
            name: "prompt-two",
            description: "Second prompt",
          },
        ],
      };

      const mockVersionDetail1: OpikApi.PromptVersionDetail = {
        id: "version-1",
        promptId: "prompt-1",
        commit: "commit1",
        template: "Template 1",
        type: "mustache",
      };

      const mockVersionDetail2: OpikApi.PromptVersionDetail = {
        id: "version-2",
        promptId: "prompt-2",
        commit: "commit2",
        template: "Template 2",
        type: "mustache",
      };

      getPromptsSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptsResponse)
      );

      retrievePromptVersionSpy
        .mockImplementationOnce(() =>
          createMockHttpResponsePromise(mockVersionDetail1)
        )
        .mockImplementationOnce(() =>
          createMockHttpResponsePromise(mockVersionDetail2)
        );

      const results = await client.searchPrompts();

      expect(getPromptsSpy).toHaveBeenCalledWith(
        {
          filters: undefined,
          size: 1000,
        },
        client.api.requestOptions
      );
      expect(results).toHaveLength(2);
      expect(results[0]).toBeInstanceOf(Prompt);
      expect(results[0].name).toBe("prompt-one");
      expect(results[1].name).toBe("prompt-two");
    });

    it("should search prompts with simple OQL filter", async () => {
      const mockPromptsResponse = {
        content: [
          {
            id: "prompt-1",
            name: "filtered-prompt",
            description: "Filtered",
          },
        ],
      };

      const mockVersionDetail: OpikApi.PromptVersionDetail = {
        id: "version-1",
        promptId: "prompt-1",
        commit: "commit1",
        template: "Filtered template",
        type: "mustache",
      };

      getPromptsSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptsResponse)
      );

      retrievePromptVersionSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockVersionDetail)
      );

      const results = await client.searchPrompts('name = "filtered-prompt"');

      expect(getPromptsSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          filters: expect.any(String),
          size: 1000,
        }),
        client.api.requestOptions
      );
      expect(results).toHaveLength(1);
      expect(results[0].name).toBe("filtered-prompt");
    });

    it("should search prompts with complex multi-condition filters", async () => {
      const mockPromptsResponse = {
        content: [
          {
            id: "prompt-1",
            name: "complex-prompt",
            description: "Complex filter",
          },
        ],
      };

      const mockVersionDetail: OpikApi.PromptVersionDetail = {
        id: "version-1",
        promptId: "prompt-1",
        commit: "commit1",
        template: "Complex template",
        type: "mustache",
      };

      getPromptsSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptsResponse)
      );

      retrievePromptVersionSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockVersionDetail)
      );

      await client.searchPrompts(
        'tags contains "alpha" AND name contains "summary"'
      );

      expect(getPromptsSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          filters: expect.any(String),
          size: 1000,
        }),
        client.api.requestOptions
      );

      // Verify OQL parsing integration
      const callArgs = getPromptsSpy.mock.calls[0]?.[0];
      expect(callArgs).toBeDefined();
      expect(callArgs?.filters).toBeDefined();
      const filters = JSON.parse(callArgs!.filters!);
      expect(filters).toBeInstanceOf(Array);
      expect(filters.length).toBeGreaterThan(0);
    });

    it("should integrate with OQL parsing", async () => {
      const mockPromptsResponse = {
        content: [],
      };

      getPromptsSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptsResponse)
      );

      await client.searchPrompts('created_by = "user@example.com"');

      const callArgs = getPromptsSpy.mock.calls[0]?.[0];
      expect(callArgs).toBeDefined();
      expect(callArgs?.filters).toBeDefined();

      // Verify OQL was parsed and converted to JSON
      const filters = JSON.parse(callArgs!.filters!);
      expect(filters).toBeInstanceOf(Array);
    });

    it("should handle pagination with size 1000", async () => {
      const mockPromptsResponse = {
        content: [],
      };

      getPromptsSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptsResponse)
      );

      await client.searchPrompts();

      expect(getPromptsSpy).toHaveBeenCalledWith(
        expect.objectContaining({
          size: 1000,
        }),
        client.api.requestOptions
      );
    });

    it("should return empty array when no results", async () => {
      const mockPromptsResponse = {
        content: [],
      };

      getPromptsSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptsResponse)
      );

      const results = await client.searchPrompts();

      expect(results).toEqual([]);
      expect(results).toHaveLength(0);
    });

    it("should propagate invalid OQL syntax errors", async () => {
      // Mock OQL to throw an error for invalid syntax
      const invalidFilter = "invalid OQL syntax ===";

      await expect(client.searchPrompts(invalidFilter)).rejects.toThrow();

      expect(loggerErrorSpy).toHaveBeenCalledWith(
        "Failed to search prompts",
        expect.objectContaining({
          error: expect.any(Error),
        })
      );
    });

    it("should create prompt with tags and description in options", async () => {
      const mockVersionDetail: OpikApi.PromptVersionDetail = {
        id: "version-id",
        promptId: "prompt-id",
        commit: "abc123de",
        template: "Test {{var}}",
        type: "mustache",
        createdAt: new Date("2024-01-01"),
      };

      const mockPromptData: OpikApi.PromptPublic = {
        id: "prompt-id",
        name: "prompt-with-tags",
        description: "Test description",
        tags: ["tag1", "tag2"],
      };

      retrievePromptVersionSpy.mockImplementationOnce(() => {
        throw new OpikApiError({ message: "Not found", statusCode: 404 });
      });

      createPromptVersionSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockVersionDetail)
      );

      getPromptByIdSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptData)
      );

      const updatePropertiesSpy = vi.fn();
      vi.spyOn(Prompt.prototype, "updateProperties").mockImplementation(
        updatePropertiesSpy
      );

      await client.createPrompt({
        name: "prompt-with-tags",
        prompt: "Test {{var}}",
        description: "Test description",
        tags: ["tag1", "tag2"],
      });

      expect(updatePropertiesSpy).toHaveBeenCalledWith({
        description: "Test description",
        tags: ["tag1", "tag2"],
      });
    });

    it("should handle API errors during search", async () => {
      const apiError = new OpikApiError({
        message: "Search failed",
        statusCode: 500,
      });

      getPromptsSpy.mockImplementationOnce(() => {
        throw apiError;
      });

      await expect(client.searchPrompts()).rejects.toThrow();

      expect(loggerErrorSpy).toHaveBeenCalledWith(
        "Failed to search prompts",
        expect.objectContaining({
          error: apiError,
        })
      );
    });

    it("should handle prompts without names gracefully", async () => {
      const mockPromptsResponse = {
        content: [
          {
            id: "prompt-1",
            name: "valid-prompt",
          },
          {
            id: "prompt-2",
            name: undefined as unknown as string, // Invalid prompt without name
          },
          {
            id: "prompt-3",
            name: "another-valid",
          },
        ],
      };

      const mockVersionDetail1: OpikApi.PromptVersionDetail = {
        id: "version-1",
        promptId: "prompt-1",
        commit: "commit1",
        template: "Template 1",
        type: "mustache",
      };

      const mockVersionDetail3: OpikApi.PromptVersionDetail = {
        id: "version-3",
        promptId: "prompt-3",
        commit: "commit3",
        template: "Template 3",
        type: "mustache",
      };

      getPromptsSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptsResponse)
      );

      retrievePromptVersionSpy
        .mockImplementationOnce(() =>
          createMockHttpResponsePromise(mockVersionDetail1)
        )
        .mockImplementationOnce(() =>
          createMockHttpResponsePromise(mockVersionDetail3)
        );

      const results = await client.searchPrompts();

      // Should filter out prompts without names
      expect(results).toHaveLength(2);
      expect(results[0].name).toBe("valid-prompt");
      expect(results[1].name).toBe("another-valid");
    });

    it("should handle version retrieval failures gracefully", async () => {
      const mockPromptsResponse = {
        content: [
          {
            id: "prompt-1",
            name: "prompt-one",
          },
          {
            id: "prompt-2",
            name: "prompt-two",
          },
        ],
      };

      const mockVersionDetail1: OpikApi.PromptVersionDetail = {
        id: "version-1",
        promptId: "prompt-1",
        commit: "commit1",
        template: "Template 1",
        type: "mustache",
      };

      getPromptsSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(mockPromptsResponse)
      );

      // First prompt succeeds, second fails
      retrievePromptVersionSpy
        .mockImplementationOnce(() =>
          createMockHttpResponsePromise(mockVersionDetail1)
        )
        .mockImplementationOnce(() => {
          throw new Error("Failed to retrieve version");
        });

      const results = await client.searchPrompts();

      // Should only include successfully retrieved prompts
      expect(results).toHaveLength(1);
      expect(results[0].name).toBe("prompt-one");
      expect(loggerDebugSpy).toHaveBeenCalledWith(
        "Failed to get version for prompt",
        expect.objectContaining({
          name: "prompt-two",
        })
      );
    });
  });

  describe("deletePrompts", () => {
    it("should delete single prompt", async () => {
      deletePromptsBatchSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(undefined)
      );

      await client.deletePrompts(["prompt-id-1"]);

      expect(deletePromptsBatchSpy).toHaveBeenCalledWith(
        { ids: ["prompt-id-1"] },
        client.api.requestOptions
      );
      expect(loggerDebugSpy).toHaveBeenCalledWith("Deleting prompts in batch", {
        count: 1,
      });
      expect(loggerInfoSpy).toHaveBeenCalledWith(
        "Successfully deleted prompts",
        { count: 1 }
      );
    });

    it("should delete multiple prompts", async () => {
      const ids = ["prompt-id-1", "prompt-id-2", "prompt-id-3"];

      deletePromptsBatchSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(undefined)
      );

      await client.deletePrompts(ids);

      expect(deletePromptsBatchSpy).toHaveBeenCalledWith(
        { ids },
        client.api.requestOptions
      );
      expect(loggerInfoSpy).toHaveBeenCalledWith(
        "Successfully deleted prompts",
        { count: 3 }
      );
    });

    it("should handle empty array", async () => {
      deletePromptsBatchSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(undefined)
      );

      await client.deletePrompts([]);

      expect(deletePromptsBatchSpy).toHaveBeenCalledWith(
        { ids: [] },
        client.api.requestOptions
      );
      expect(loggerDebugSpy).toHaveBeenCalledWith("Deleting prompts in batch", {
        count: 0,
      });
      expect(loggerInfoSpy).toHaveBeenCalledWith(
        "Successfully deleted prompts",
        { count: 0 }
      );
    });

    it("should call API with all IDs", async () => {
      const ids = ["id-1", "id-2"];

      deletePromptsBatchSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(undefined)
      );

      await client.deletePrompts(ids);

      expect(deletePromptsBatchSpy).toHaveBeenCalledTimes(1);
      expect(deletePromptsBatchSpy).toHaveBeenCalledWith(
        { ids },
        client.api.requestOptions
      );
    });

    it("should handle errors during deletion", async () => {
      const errorMessage = "Failed to delete";
      const apiError = new OpikApiError({
        message: errorMessage,
        statusCode: 500,
      });

      deletePromptsBatchSpy.mockImplementationOnce(() => {
        throw apiError;
      });

      await expect(client.deletePrompts(["error-id"])).rejects.toThrow();

      expect(loggerErrorSpy).toHaveBeenCalledWith(
        "Failed to delete prompts",
        expect.objectContaining({
          count: 1,
          error: apiError,
        })
      );
    });
  });

  describe("setPromptEnvironment error mapping", () => {
    const versionResponse: OpikApi.PromptVersionDetail = {
      id: "version-id",
      promptId: "prompt-id",
      commit: "abc123de",
      template: "Hello",
      type: "mustache",
      createdAt: new Date("2024-01-01"),
    };

    it("maps 404 on retrieve_prompt_version (no commit) to PromptNotFoundError", async () => {
      retrievePromptVersionSpy.mockImplementationOnce(() => {
        throw new OpikApiError({ message: "Not found", statusCode: 404 });
      });

      await expect(
        client.setPromptEnvironment({
          name: "missing-prompt",
          environment: "staging",
        })
      ).rejects.toMatchObject({
        name: "PromptNotFoundError",
        message: expect.stringContaining("missing-prompt"),
      });
    });

    it("maps 404 on retrieve_prompt_version (with commit) to PromptNotFoundError mentioning the commit", async () => {
      retrievePromptVersionSpy.mockImplementationOnce(() => {
        throw new OpikApiError({ message: "Not found", statusCode: 404 });
      });

      await expect(
        client.setPromptEnvironment({
          name: "env-prompt",
          environment: "staging",
          commit: "deadbeef",
        })
      ).rejects.toMatchObject({
        name: "PromptNotFoundError",
        message: expect.stringContaining("deadbeef"),
      });
    });

    it("maps 404 on set_prompt_version_environment to EnvironmentNotFoundError", async () => {
      retrievePromptVersionSpy.mockImplementation(() =>
        createMockHttpResponsePromise(versionResponse)
      );
      const setEnvSpy = vi
        .spyOn(client.api.prompts, "setPromptVersionEnvironment")
        .mockImplementation(() => {
          throw new OpikApiError({ message: "Not found", statusCode: 404 });
        });

      try {
        await expect(
          client.setPromptEnvironment({
            name: "env-prompt",
            environment: "unknown-env",
          })
        ).rejects.toThrow(EnvironmentNotFoundError);
        await expect(
          client.setPromptEnvironment({
            name: "env-prompt",
            environment: "unknown-env",
          })
        ).rejects.toMatchObject({
          message: expect.stringContaining("unknown-env"),
        });
      } finally {
        setEnvSpy.mockRestore();
      }
    });

    it("maps 422 on set_prompt_version_environment to PromptVersionNotAssignableToEnvironmentError", async () => {
      retrievePromptVersionSpy.mockImplementation(() =>
        createMockHttpResponsePromise(versionResponse)
      );
      const setEnvSpy = vi
        .spyOn(client.api.prompts, "setPromptVersionEnvironment")
        .mockImplementation(() => {
          throw new OpikApiError({
            message: "Unprocessable",
            statusCode: 422,
          });
        });

      try {
        await expect(
          client.setPromptEnvironment({
            name: "mask-prompt",
            environment: "staging",
          })
        ).rejects.toThrow(PromptVersionNotAssignableToEnvironmentError);
        await expect(
          client.setPromptEnvironment({
            name: "mask-prompt",
            environment: "staging",
          })
        ).rejects.toMatchObject({
          message: expect.stringContaining("internal-only"),
        });
      } finally {
        setEnvSpy.mockRestore();
      }
    });

    it("rethrows non-mapped errors unchanged", async () => {
      retrievePromptVersionSpy.mockImplementationOnce(() =>
        createMockHttpResponsePromise(versionResponse)
      );
      const apiError = new OpikApiError({
        message: "Boom",
        statusCode: 500,
      });
      const setEnvSpy = vi
        .spyOn(client.api.prompts, "setPromptVersionEnvironment")
        .mockImplementationOnce(() => {
          throw apiError;
        });

      try {
        await expect(
          client.setPromptEnvironment({
            name: "env-prompt",
            environment: "staging",
          })
        ).rejects.toBe(apiError);
        expect(setEnvSpy).toHaveBeenCalledOnce();
      } finally {
        setEnvSpy.mockRestore();
      }
    });

    it("does not call set_prompt_version_environment when the prompt is not found", async () => {
      retrievePromptVersionSpy.mockImplementationOnce(() => {
        throw new OpikApiError({ message: "Not found", statusCode: 404 });
      });
      const setEnvSpy = vi.spyOn(
        client.api.prompts,
        "setPromptVersionEnvironment"
      );

      try {
        await expect(
          client.setPromptEnvironment({
            name: "missing-prompt",
            environment: "staging",
          })
        ).rejects.toThrow(PromptNotFoundError);
        expect(setEnvSpy).not.toHaveBeenCalled();
      } finally {
        setEnvSpy.mockRestore();
      }
    });
  });

  describe("flush integration", () => {
    it("should flush all batch queues (prompts are synchronous)", async () => {
      const traceBatchQueueFlushSpy = vi.spyOn(client.traceBatchQueue, "flush");
      const spanBatchQueueFlushSpy = vi.spyOn(client.spanBatchQueue, "flush");

      await client.flush();

      expect(traceBatchQueueFlushSpy).toHaveBeenCalled();
      expect(spanBatchQueueFlushSpy).toHaveBeenCalled();
      // Note: Prompt operations are synchronous and don't use batching
      expect(loggerInfoSpy).toHaveBeenCalledWith(
        "Successfully flushed all data to Opik"
      );
    });

    it("should handle errors during flush", async () => {
      const traceBatchQueueFlushSpy = vi.spyOn(client.traceBatchQueue, "flush");
      traceBatchQueueFlushSpy.mockImplementationOnce(() => {
        throw new Error("Flush failed");
      });

      await client.flush();

      expect(loggerErrorSpy).toHaveBeenCalled();
    });
  });

  describe("getPrompt — auto-injection deduplication", () => {
    const mockPromptPublic = {
      id: "prompt-id",
      name: "my-prompt",
    };
    const mockVersionDetail: OpikApi.PromptVersionDetail = {
      id: "version-id",
      promptId: "prompt-id",
      commit: "abc123",
      template: "Hello!",
      type: "mustache",
      createdAt: new Date("2024-01-01"),
      createdBy: "test-user",
      templateStructure: PromptTemplateStructure.Text,
    };

    function setupPromptMocks() {
      getPromptsSpy.mockResolvedValue({ content: [mockPromptPublic] } as never);
      retrievePromptVersionSpy.mockResolvedValue(mockVersionDetail as never);
    }

    function makeTrackContext() {
      vi.spyOn(client.traceBatchQueue, "update").mockReturnValue(undefined as never);
      vi.spyOn(client.spanBatchQueue, "update").mockReturnValue(undefined as never);
      const trace = new Trace({ id: "trace-id", projectName: "opik-sdk-typescript" } as never, client);
      const span = new Span({ id: "span-id", traceId: "trace-id", projectName: "opik-sdk-typescript" } as never, client);
      return { trace, span };
    }

    it("injects prompt into trace and span when inside a track context", async () => {
      setupPromptMocks();
      const { trace, span } = makeTrackContext();

      await trackStorage.run(
        { trace, span } as never,
        () => client.getPrompt({ name: "my-prompt" })
      );

      expect((trace.data.metadata as Record<string, unknown>)?.opik_prompts as PromptInfoDict[]).toHaveLength(1);
      expect((span.data.metadata as Record<string, unknown>)?.opik_prompts as PromptInfoDict[]).toHaveLength(1);
    });

    it("does not inject the same prompt twice when called twice inside a track context", async () => {
      setupPromptMocks();
      const { trace, span } = makeTrackContext();

      await trackStorage.run(
        { trace, span } as never,
        async () => {
          await client.getPrompt({ name: "my-prompt" });
          await client.getPrompt({ name: "my-prompt" });
        }
      );

      expect((trace.data.metadata as Record<string, unknown>)?.opik_prompts as PromptInfoDict[]).toHaveLength(1);
      expect((span.data.metadata as Record<string, unknown>)?.opik_prompts as PromptInfoDict[]).toHaveLength(1);
    });

    it("injects two different prompts, both appear exactly once", async () => {
      const mockPromptB = { id: "prompt-id-b", name: "other-prompt" };
      const mockVersionDetailB: OpikApi.PromptVersionDetail = {
        id: "version-id-b",
        promptId: "prompt-id-b",
        commit: "def456",
        template: "World!",
        type: "mustache",
        createdAt: new Date("2024-01-01"),
        createdBy: "test-user",
        templateStructure: PromptTemplateStructure.Text,
      };

      const { trace, span } = makeTrackContext();

      await trackStorage.run(
        { trace, span } as never,
        async () => {
          getPromptsSpy.mockResolvedValueOnce({ content: [mockPromptPublic] } as never);
          retrievePromptVersionSpy.mockResolvedValueOnce(mockVersionDetail as never);
          await client.getPrompt({ name: "my-prompt" });

          getPromptsSpy.mockResolvedValueOnce({ content: [mockPromptB] } as never);
          retrievePromptVersionSpy.mockResolvedValueOnce(mockVersionDetailB as never);
          await client.getPrompt({ name: "other-prompt" });
        }
      );

      const injected = (trace.data.metadata as Record<string, unknown>)?.opik_prompts as PromptInfoDict[];
      expect(injected).toHaveLength(2);
      const ids = injected.map((p) => p.id);
      expect(ids).toContain("prompt-id");
      expect(ids).toContain("prompt-id-b");
    });

    it("does not inject when outside a track context", async () => {
      setupPromptMocks();
      // Should not throw and should return the prompt normally
      const result = await client.getPrompt({ name: "my-prompt" });
      expect(result).toBeInstanceOf(Prompt);
    });
  });
});
