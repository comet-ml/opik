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
      expect(
        mockOpikClient.api.prompts.restorePromptVersion
      ).toHaveBeenCalledWith(
        mockPrompt.id,
        "version-123",
        mockOpikClient.api.requestOptions
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
  });

  describe("updateProperties()", () => {
    it("should update name via API", async () => {
      await mockPrompt.updateProperties({ name: "new-name" });

      expect(mockPrompt.name).toBe("new-name");
      expect(mockOpikClient.api.prompts.updatePrompt).toHaveBeenCalledWith(
        mockPrompt.id,
        expect.objectContaining({ name: "new-name" }),
        mockOpikClient.api.requestOptions
      );
    });

    it("should update description via API", async () => {
      await mockPrompt.updateProperties({ description: "New description" });

      expect(mockPrompt.description).toBe("New description");
      expect(mockOpikClient.api.prompts.updatePrompt).toHaveBeenCalledWith(
        mockPrompt.id,
        expect.objectContaining({ description: "New description" }),
        mockOpikClient.api.requestOptions
      );
    });

    it("should update tags via API", async () => {
      const newTags = ["tag1", "tag2"];
      await mockPrompt.updateProperties({ tags: newTags });

      expect(mockPrompt.tags).toEqual(newTags);
      expect(Object.isFrozen(mockPrompt.tags)).toBe(true);
      expect(mockOpikClient.api.prompts.updatePrompt).toHaveBeenCalledWith(
        mockPrompt.id,
        expect.objectContaining({ tags: newTags }),
        mockOpikClient.api.requestOptions
      );
    });

    it("should support partial updates", async () => {
      await mockPrompt.updateProperties({ description: "Only description" });

      expect(mockPrompt.name).toBe("test-prompt"); // unchanged
      expect(mockPrompt.description).toBe("Only description");
    });

    it("should support method chaining", async () => {
      const result = await mockPrompt.updateProperties({ name: "chained" });

      expect(result).toBe(mockPrompt);
    });

    it("should update multiple properties at once", async () => {
      await mockPrompt.updateProperties({
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
    it("should delete via API", async () => {
      await mockPrompt.delete();

      expect(mockOpikClient.deletePrompts).toHaveBeenCalledWith([
        mockPrompt.id,
      ]);
    });

    it("should delete without modifying prompt state", async () => {
      const nameBeforeDelete = mockPrompt.name;
      const commitBeforeDelete = mockPrompt.commit;

      await mockPrompt.delete();

      expect(mockPrompt.name).toBe(nameBeforeDelete);
      expect(mockPrompt.commit).toBe(commitBeforeDelete);
    });

    it("should allow deletion after update", async () => {
      await mockPrompt.updateProperties({ name: "updated" });
      await mockPrompt.delete();

      expect(mockOpikClient.api.prompts.updatePrompt).toHaveBeenCalled();
      expect(mockOpikClient.deletePrompts).toHaveBeenCalledWith([
        mockPrompt.id,
      ]);
    });
  });
});
