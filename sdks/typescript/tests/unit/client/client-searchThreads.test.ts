import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { Opik } from "opik";
import { MockInstance } from "vitest";
import { SearchTimeoutError } from "@/errors";
import * as searchHelpers from "@/utils/searchHelpers";
import { createMockThreads, createMockThread } from "../utils/searchHelpersMocks";

vi.mock("@/utils/searchHelpers", async () => {
  const actual = await vi.importActual<typeof searchHelpers>(
    "@/utils/searchHelpers"
  );
  return {
    ...actual,
    searchThreadsWithFilters: vi.fn(),
    searchAndWaitForDone: vi.fn(),
  };
});

describe("OpikClient searchThreads", () => {
  let client: Opik;
  let searchThreadsWithFiltersSpy: MockInstance<
    typeof searchHelpers.searchThreadsWithFilters
  >;
  let searchAndWaitForDoneSpy: MockInstance<
    typeof searchHelpers.searchAndWaitForDone
  >;

  beforeEach(() => {
    client = new Opik({ projectName: "test-project" });

    searchThreadsWithFiltersSpy = vi
      .spyOn(searchHelpers, "searchThreadsWithFilters")
      .mockResolvedValue([]);

    searchAndWaitForDoneSpy = vi
      .spyOn(searchHelpers, "searchAndWaitForDone")
      .mockImplementation(async (fn) => await fn());
  });

  afterEach(() => {
    searchThreadsWithFiltersSpy.mockRestore();
    searchAndWaitForDoneSpy.mockRestore();
  });

  describe("basic search functionality", () => {
    it("should search threads with default config values", async () => {
      const mockThreads = createMockThreads(2);
      searchThreadsWithFiltersSpy.mockResolvedValue(mockThreads);

      const result = await client.searchThreads();

      expect(result).toEqual(mockThreads);
      expect(searchThreadsWithFiltersSpy).toHaveBeenCalledWith(
        client.api,
        "test-project",
        null,
        1000,
        true
      );
    });

    it("should override default values with provided options", async () => {
      searchThreadsWithFiltersSpy.mockResolvedValue([]);

      await client.searchThreads({
        projectName: "custom-project",
        maxResults: 50,
        truncate: false,
      });

      const callArgs = searchThreadsWithFiltersSpy.mock.calls[0];
      expect(callArgs[1]).toBe("custom-project");
      expect(callArgs[3]).toBe(50);
      expect(callArgs[4]).toBe(false);
    });
  });

  describe("OQL filter parsing", () => {
    it("should parse OQL filter string and pass to API", async () => {
      searchThreadsWithFiltersSpy.mockResolvedValue([]);

      await client.searchThreads({
        filterString: 'status = "active" and duration > 100',
      });

      const callArgs = searchThreadsWithFiltersSpy.mock.calls[0];
      const filters = callArgs[2];

      expect(filters).toBeDefined();
      expect(filters).toHaveLength(2);
      expect(filters![0]).toMatchObject({ field: "status", operator: "=" });
      expect(filters![1]).toMatchObject({ field: "duration", operator: ">" });
    });

    it("should handle filters with feedback_scores keys", async () => {
      searchThreadsWithFiltersSpy.mockResolvedValue([]);

      await client.searchThreads({
        filterString: 'feedback_scores."quality" > 0.8',
      });

      const callArgs = searchThreadsWithFiltersSpy.mock.calls[0];
      const filters = callArgs[2];

      expect(filters![0]).toMatchObject({
        field: "feedback_scores",
        key: "quality",
      });
    });

    it("should propagate OQL parsing errors", async () => {
      await expect(
        client.searchThreads({ filterString: "invalid ===" })
      ).rejects.toThrow();
    });
  });

  describe("polling with waitForAtLeast", () => {
    beforeEach(() => {
      vi.useFakeTimers();
      searchAndWaitForDoneSpy.mockImplementation(async (fn) => await fn());
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it("should not use polling when waitForAtLeast is not provided", async () => {
      const mockThreads = createMockThreads(1);
      searchThreadsWithFiltersSpy.mockResolvedValue(mockThreads);

      const result = await client.searchThreads();

      expect(result).toEqual(mockThreads);
      expect(searchAndWaitForDoneSpy).not.toHaveBeenCalled();
    });

    it("should use polling when waitForAtLeast is provided", async () => {
      const mockThreads = createMockThreads(5);
      searchThreadsWithFiltersSpy.mockResolvedValue(mockThreads);
      searchAndWaitForDoneSpy.mockResolvedValue(mockThreads);

      await client.searchThreads({
        waitForAtLeast: 5,
        waitForTimeout: 10,
      });

      expect(searchAndWaitForDoneSpy).toHaveBeenCalledWith(
        expect.any(Function),
        5,
        10000,
        5000
      );
    });

    it("should throw SearchTimeoutError when insufficient results after timeout", async () => {
      const mockThread = createMockThreads(1);
      searchThreadsWithFiltersSpy.mockResolvedValue(mockThread);
      searchAndWaitForDoneSpy.mockResolvedValue(mockThread);

      await expect(
        client.searchThreads({
          waitForAtLeast: 10,
          waitForTimeout: 1,
        })
      ).rejects.toThrow(SearchTimeoutError);

      await expect(
        client.searchThreads({
          waitForAtLeast: 10,
          waitForTimeout: 1,
        })
      ).rejects.toThrow(/expected 10 threads, but only 1 were found/);
    });

    it("should use default timeout of 60 seconds", async () => {
      searchThreadsWithFiltersSpy.mockResolvedValue([]);
      searchAndWaitForDoneSpy.mockResolvedValue([]);

      await expect(
        client.searchThreads({ waitForAtLeast: 100 })
      ).rejects.toThrow(/Timeout after 60 seconds/);
    });
  });

  describe("complete workflow integration", () => {
    it("should handle complex search with all options", async () => {
      const mockThreads = createMockThreads(3);

      searchAndWaitForDoneSpy.mockImplementation(async (searchFn) => {
        return await searchFn();
      });
      searchThreadsWithFiltersSpy.mockResolvedValue(mockThreads);

      const result = await client.searchThreads({
        projectName: "custom-project",
        filterString: 'status = "active" and duration > 100',
        maxResults: 50,
        truncate: false,
        waitForAtLeast: 3,
        waitForTimeout: 10,
      });

      expect(result).toEqual(mockThreads);

      const callArgs = searchThreadsWithFiltersSpy.mock.calls[0];
      expect(callArgs[1]).toBe("custom-project");
      expect(callArgs[2]).toHaveLength(2);
      expect(callArgs[3]).toBe(50);
      expect(callArgs[4]).toBe(false);

      expect(searchAndWaitForDoneSpy).toHaveBeenCalled();
    });
  });

  describe("error handling", () => {
    it("should propagate API errors", async () => {
      const apiError = new Error("API request failed");
      searchThreadsWithFiltersSpy.mockRejectedValue(apiError);

      await expect(client.searchThreads()).rejects.toThrow("API request failed");
    });

    it("should handle network timeouts", async () => {
      const networkError = new Error("Network timeout");
      searchThreadsWithFiltersSpy.mockRejectedValue(networkError);

      await expect(client.searchThreads()).rejects.toThrow("Network timeout");
    });
  });

  describe("edge cases", () => {
    it("should handle empty results gracefully", async () => {
      searchThreadsWithFiltersSpy.mockResolvedValue([]);

      const result = await client.searchThreads();

      expect(result).toEqual([]);
    });

    it("should handle large result sets", async () => {
      const mockThreads = createMockThreads(1000);
      searchThreadsWithFiltersSpy.mockResolvedValue(mockThreads);

      const result = await client.searchThreads({ maxResults: 1000 });

      expect(result).toHaveLength(1000);
    });

    it("should preserve all thread fields", async () => {
      const completeThread = createMockThread({
        id: "thread-1",
        threadModelId: "thread-model-1",
        status: "active",
        numberOfMessages: 10,
        duration: 600,
        totalEstimatedCost: 0.15,
        tags: ["tag1", "tag2"],
        feedbackScores: [{ name: "quality", value: 0.9, source: "ui" }],
      });

      searchThreadsWithFiltersSpy.mockResolvedValue([completeThread]);

      const result = await client.searchThreads();

      expect(result[0]).toMatchObject({
        id: "thread-1",
        threadModelId: "thread-model-1",
        status: "active",
        numberOfMessages: 10,
        duration: 600,
        totalEstimatedCost: 0.15,
        tags: ["tag1", "tag2"],
      });
    });

    it("should handle empty filter string", async () => {
      searchThreadsWithFiltersSpy.mockResolvedValue([]);

      const result = await client.searchThreads({ filterString: "" });

      expect(result).toEqual([]);
      expect(searchThreadsWithFiltersSpy).toHaveBeenCalledWith(
        client.api,
        "test-project",
        null,
        1000,
        true
      );
    });

    it("should handle maxResults of 1", async () => {
      const mockThread = createMockThreads(1);
      searchThreadsWithFiltersSpy.mockResolvedValue(mockThread);

      const result = await client.searchThreads({ maxResults: 1 });

      expect(result).toHaveLength(1);
      const callArgs = searchThreadsWithFiltersSpy.mock.calls[0];
      expect(callArgs[3]).toBe(1);
    });

    it("should handle threads with no tags", async () => {
      const threadWithoutTags = createMockThread({
        id: "thread-no-tags",
        tags: undefined,
      });

      searchThreadsWithFiltersSpy.mockResolvedValue([threadWithoutTags]);

      const result = await client.searchThreads();

      expect(result[0].tags).toBeUndefined();
    });

    it("should handle threads with inactive status", async () => {
      const inactiveThread = createMockThread({
        id: "thread-inactive",
        status: "inactive",
      });

      searchThreadsWithFiltersSpy.mockResolvedValue([inactiveThread]);

      const result = await client.searchThreads({
        filterString: 'status = "inactive"',
      });

      expect(result[0].status).toBe("inactive");
    });

    it("should handle numeric filters for duration", async () => {
      searchThreadsWithFiltersSpy.mockResolvedValue([]);

      await client.searchThreads({
        filterString: "duration > 100",
      });

      const callArgs = searchThreadsWithFiltersSpy.mock.calls[0];
      const filters = callArgs[2];

      expect(filters).toBeDefined();
      expect(filters![0]).toMatchObject({
        field: "duration",
        operator: ">",
        value: "100",
      });
    });

    it("should handle numeric filters for number_of_messages", async () => {
      searchThreadsWithFiltersSpy.mockResolvedValue([]);

      await client.searchThreads({
        filterString: "number_of_messages >= 5",
      });

      const callArgs = searchThreadsWithFiltersSpy.mock.calls[0];
      const filters = callArgs[2];

      expect(filters).toBeDefined();
      expect(filters![0]).toMatchObject({
        field: "number_of_messages",
        operator: ">=",
      });
    });

    it("should handle filters for feedback_scores", async () => {
      searchThreadsWithFiltersSpy.mockResolvedValue([]);

      await client.searchThreads({
        filterString: "feedback_scores.quality > 0.8",
      });

      const callArgs = searchThreadsWithFiltersSpy.mock.calls[0];
      const filters = callArgs[2];

      expect(filters).toBeDefined();
      expect(filters![0]).toMatchObject({
        field: "feedback_scores",
        key: "quality",
        operator: ">",
        value: "0.8",
      });
    });

    it("should handle truncate parameter", async () => {
      searchThreadsWithFiltersSpy.mockResolvedValue([]);

      await client.searchThreads({
        truncate: false,
      });

      const callArgs = searchThreadsWithFiltersSpy.mock.calls[0];
      expect(callArgs[4]).toBe(false);
    });

    it("should handle custom projectName parameter", async () => {
      searchThreadsWithFiltersSpy.mockResolvedValue([]);

      await client.searchThreads({
        projectName: "custom-project",
      });

      const callArgs = searchThreadsWithFiltersSpy.mock.calls[0];
      expect(callArgs[1]).toBe("custom-project");
    });
  });
});
