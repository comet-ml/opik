import { describe, it, expect, beforeEach, vi } from "vitest";
import { Prompt } from "../../../src/opik/prompt/Prompt";
import { PromptVersion } from "../../../src/opik/prompt/PromptVersion";
import {
  createMockOpikClient,
  basicPromptData,
  createMockVersionDetailArray,
  createMockVersionDetail,
} from "./fixtures";
import type { OpikClient } from "../../../src/opik/client/Client";

describe("Prompt - Instance Methods", () => {
  let mockOpikClient: OpikClient;
  let mockPrompt: Prompt;

  beforeEach(() => {
    mockOpikClient = createMockOpikClient();
    mockPrompt = new Prompt(
      { ...basicPromptData, commit: "current123" },
      mockOpikClient
    );
  });

  describe("getVersions()", () => {
    it("should retrieve all versions with pagination", async () => {
      const page1 = createMockVersionDetailArray(100);
      const page2 = createMockVersionDetailArray(50);

      vi.mocked(mockOpikClient.api.prompts.getPromptVersions)
        .mockResolvedValueOnce({
          content: page1,
          page: 1,
          size: 100,
          total: 150,
        })
        .mockResolvedValueOnce({
          content: page2,
          page: 2,
          size: 100,
          total: 150,
        });

      const versions = await mockPrompt.getVersions();

      expect(versions).toHaveLength(150);
      expect(versions[0]).toBeInstanceOf(PromptVersion);
      expect(mockOpikClient.promptBatchQueue.flush).toHaveBeenCalled();
      expect(
        mockOpikClient.api.prompts.getPromptVersions
      ).toHaveBeenCalledTimes(2);
    });

    it("should handle single page of results", async () => {
      const versions = createMockVersionDetailArray(50);

      vi.mocked(
        mockOpikClient.api.prompts.getPromptVersions
      ).mockResolvedValueOnce({
        content: versions,
        page: 1,
        size: 100,
        total: 50,
      });

      const result = await mockPrompt.getVersions();

      expect(result).toHaveLength(50);
      expect(
        mockOpikClient.api.prompts.getPromptVersions
      ).toHaveBeenCalledTimes(1);
    });

    it("should handle empty version history", async () => {
      vi.mocked(
        mockOpikClient.api.prompts.getPromptVersions
      ).mockResolvedValueOnce({
        content: [],
        page: 1,
        size: 100,
        total: 0,
      });

      const versions = await mockPrompt.getVersions();

      expect(versions).toHaveLength(0);
    });

    it("should flush queue before fetching versions", async () => {
      vi.mocked(
        mockOpikClient.api.prompts.getPromptVersions
      ).mockResolvedValueOnce({
        content: [],
        page: 1,
        size: 100,
        total: 0,
      });

      await mockPrompt.getVersions();

      expect(mockOpikClient.promptBatchQueue.flush).toHaveBeenCalledBefore(
        mockOpikClient.api.prompts.getPromptVersions as unknown as never
      );
    });

    it("should propagate API errors", async () => {
      const apiError = new Error("API connection failed");
      vi.mocked(
        mockOpikClient.api.prompts.getPromptVersions
      ).mockRejectedValueOnce(apiError);

      await expect(mockPrompt.getVersions()).rejects.toThrow(
        "API connection failed"
      );
    });
  });

  describe("useVersion()", () => {
    it("should restore a version and return new Prompt", async () => {
      const targetVersion = new PromptVersion({
        versionId: "version-123",
        promptId: "test-prompt-id",
        name: "test-prompt",
        prompt: "Old template",
        commit: "old-commit",
        type: "mustache",
        createdAt: new Date("2024-01-01T00:00:00Z"),
      });

      const restoredVersionResponse = createMockVersionDetail({
        id: "new-version-456",
        commit: "new-commit",
        template: "Old template",
      });

      vi.mocked(
        mockOpikClient.api.prompts.restorePromptVersion
      ).mockResolvedValueOnce(restoredVersionResponse);

      const restoredPrompt = await mockPrompt.useVersion(targetVersion);

      expect(restoredPrompt).toBeInstanceOf(Prompt);
      expect(restoredPrompt.prompt).toBe("Old template");
      expect(restoredPrompt.commit).toBe("new-commit");
      expect(mockOpikClient.promptBatchQueue.flush).toHaveBeenCalled();
      expect(
        mockOpikClient.api.prompts.restorePromptVersion
      ).toHaveBeenCalledWith(
        mockPrompt.id,
        "version-123",
        mockOpikClient.api.requestOptions
      );
    });

    it("should flush queue before restoring", async () => {
      const targetVersion = new PromptVersion({
        versionId: "version-123",
        promptId: "test-prompt-id",
        name: "test-prompt",
        prompt: "Old template",
        commit: "old-commit",
        type: "mustache",
        createdAt: new Date("2024-01-01T00:00:00Z"),
      });

      vi.mocked(
        mockOpikClient.api.prompts.restorePromptVersion
      ).mockResolvedValueOnce(createMockVersionDetail());

      await mockPrompt.useVersion(targetVersion);

      expect(mockOpikClient.promptBatchQueue.flush).toHaveBeenCalledBefore(
        mockOpikClient.api.prompts.restorePromptVersion as unknown as never
      );
    });

    it("should propagate API errors", async () => {
      const targetVersion = new PromptVersion({
        versionId: "version-123",
        promptId: "test-prompt-id",
        name: "test-prompt",
        prompt: "Old template",
        commit: "old-commit",
        type: "mustache",
        createdAt: new Date("2024-01-01T00:00:00Z"),
      });

      const apiError = new Error("Version not found");
      vi.mocked(
        mockOpikClient.api.prompts.restorePromptVersion
      ).mockRejectedValueOnce(apiError);

      await expect(mockPrompt.useVersion(targetVersion)).rejects.toThrow(
        "Version not found"
      );
    });
  });

  describe("getVersion()", () => {
    it("should retrieve specific version by commit hash", async () => {
      const versionResponse = createMockVersionDetail({
        commit: "abc123de",
        template: "Version template",
      });

      vi.mocked(
        mockOpikClient.api.prompts.retrievePromptVersion
      ).mockResolvedValueOnce(versionResponse);

      const versionPrompt = await mockPrompt.getVersion("abc123de");

      expect(versionPrompt).toBeInstanceOf(Prompt);
      expect(versionPrompt?.commit).toBe("abc123de");
      expect(versionPrompt?.prompt).toBe("Version template");
      expect(mockOpikClient.promptBatchQueue.flush).toHaveBeenCalled();
    });

    it("should return null for non-existent version", async () => {
      const notFoundError = Object.assign(new Error("Not found"), {
        statusCode: 404,
      });

      vi.mocked(
        mockOpikClient.api.prompts.retrievePromptVersion
      ).mockRejectedValueOnce(notFoundError);

      const result = await mockPrompt.getVersion("nonexistent");

      expect(result).toBeNull();
    });

    it("should propagate non-404 errors", async () => {
      const apiError = Object.assign(new Error("Server error"), {
        statusCode: 500,
      });

      vi.mocked(
        mockOpikClient.api.prompts.retrievePromptVersion
      ).mockRejectedValueOnce(apiError);

      await expect(mockPrompt.getVersion("abc123")).rejects.toThrow(
        "Server error"
      );
    });

    it("should flush queue before retrieving version", async () => {
      vi.mocked(
        mockOpikClient.api.prompts.retrievePromptVersion
      ).mockResolvedValueOnce(createMockVersionDetail());

      await mockPrompt.getVersion("abc123");

      expect(mockOpikClient.promptBatchQueue.flush).toHaveBeenCalledBefore(
        mockOpikClient.api.prompts.retrievePromptVersion as unknown as never
      );
    });
  });

  describe("updateProperties()", () => {
    it("should update name and enqueue update", () => {
      mockPrompt.updateProperties({ name: "new-name" });

      expect(mockPrompt.name).toBe("new-name");
      expect(mockOpikClient.promptBatchQueue.update).toHaveBeenCalledWith(
        mockPrompt.id,
        expect.objectContaining({ name: "new-name" })
      );
    });

    it("should update description and enqueue update", () => {
      mockPrompt.updateProperties({ description: "New description" });

      expect(mockPrompt.description).toBe("New description");
      expect(mockOpikClient.promptBatchQueue.update).toHaveBeenCalledWith(
        mockPrompt.id,
        expect.objectContaining({ description: "New description" })
      );
    });

    it("should update tags and enqueue update", () => {
      const newTags = ["tag1", "tag2"];
      mockPrompt.updateProperties({ tags: newTags });

      expect(mockPrompt.tags).toEqual(newTags);
      expect(Object.isFrozen(mockPrompt.tags)).toBe(true);
      expect(mockOpikClient.promptBatchQueue.update).toHaveBeenCalledWith(
        mockPrompt.id,
        expect.objectContaining({ tags: newTags })
      );
    });

    it("should support partial updates", () => {
      mockPrompt.updateProperties({ description: "Only description" });

      expect(mockPrompt.name).toBe("test-prompt"); // unchanged
      expect(mockPrompt.description).toBe("Only description");
    });

    it("should support method chaining", () => {
      const result = mockPrompt.updateProperties({ name: "chained" });

      expect(result).toBe(mockPrompt);
    });

    it("should update multiple properties at once", () => {
      mockPrompt.updateProperties({
        name: "new-name",
        description: "new-desc",
        tags: ["tag1"],
      });

      expect(mockPrompt.name).toBe("new-name");
      expect(mockPrompt.description).toBe("new-desc");
      expect(mockPrompt.tags).toEqual(["tag1"]);
    });
  });

  describe("delete()", () => {
    it("should enqueue deletion", () => {
      mockPrompt.delete();

      expect(mockOpikClient.promptBatchQueue.delete).toHaveBeenCalledWith(
        mockPrompt.id
      );
    });

    it("should enqueue deletion without modifying prompt state", () => {
      const nameBeforeDelete = mockPrompt.name;
      const commitBeforeDelete = mockPrompt.commit;

      mockPrompt.delete();

      expect(mockPrompt.name).toBe(nameBeforeDelete);
      expect(mockPrompt.commit).toBe(commitBeforeDelete);
    });

    it("should allow deletion after update", () => {
      mockPrompt.updateProperties({ name: "updated" });
      mockPrompt.delete();

      expect(mockOpikClient.promptBatchQueue.update).toHaveBeenCalled();
      expect(mockOpikClient.promptBatchQueue.delete).toHaveBeenCalledWith(
        mockPrompt.id
      );
    });
  });
});
