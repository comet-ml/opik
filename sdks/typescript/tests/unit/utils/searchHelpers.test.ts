import { describe, it, expect, vi, beforeEach, afterEach } from "vitest";
import { MockInstance } from "vitest";
import {
  searchTracesWithFilters,
  searchSpansWithFilters,
  searchAndWaitForDone,
  parseFilterString,
  parseSpanFilterString,
} from "@/utils/searchHelpers";
import { OpikApiClientTemp } from "@/client/OpikApiClientTemp";
import * as OpikApi from "@/rest_api/api";
import * as streamUtils from "@/utils/stream";
import { createMockTraces, createMockSpans } from "./searchHelpersMocks";

// Mock the stream parsing module
vi.mock("@/utils/stream", async () => {
  const actual = await vi.importActual<typeof streamUtils>("@/utils/stream");
  return {
    ...actual,
    parseNdjsonStreamToArray: vi.fn(),
  };
});

describe("searchHelpers", () => {
  describe("parseFilterString", () => {
    it("should return null for empty or undefined filter strings", () => {
      expect(parseFilterString(undefined)).toBeNull();
      expect(parseFilterString("")).toBeNull();
    });

    it("should parse filters with different operators", () => {
      const testCases = [
        {
          input: 'name = "test"',
          expected: { field: "name", operator: "=", value: "test" },
        },
        {
          input: "duration > 100",
          expected: { field: "duration", operator: ">", value: "100" },
        },
        {
          input: 'tags contains "important"',
          expected: { field: "tags", operator: "contains", value: "important" },
        },
        {
          input: 'name != "failed"',
          expected: { field: "name", operator: "!=", value: "failed" },
        },
        {
          input: 'name starts_with "test_"',
          expected: { field: "name", operator: "starts_with", value: "test_" },
        },
      ];

      testCases.forEach(({ input, expected }) => {
        const result = parseFilterString(input);
        expect(result).toHaveLength(1);
        expect(result![0]).toMatchObject(expected);
      });
    });

    it("should parse filters with keys for metadata and feedback_scores", () => {
      const metadataResult = parseFilterString('metadata.version = "1.0"');
      expect(metadataResult![0]).toMatchObject({
        field: "metadata",
        key: "version",
        operator: "=",
        value: "1.0",
      });

      const feedbackResult = parseFilterString(
        'feedback_scores."Answer Relevance" < 0.8'
      );
      expect(feedbackResult![0]).toMatchObject({
        field: "feedback_scores",
        key: "Answer Relevance",
        operator: "<",
        value: "0.8",
      });
    });

    it("should parse complex queries with multiple conditions", () => {
      const result = parseFilterString(
        'name = "test" and duration > 100 and thread_id = "thread-123"'
      );

      expect(result).toHaveLength(3);
      expect(result![0].field).toBe("name");
      expect(result![1].field).toBe("duration");
      expect(result![2].field).toBe("thread_id");
    });

    it("should throw error for invalid OQL syntax", () => {
      expect(() => parseFilterString("invalid syntax ===")).toThrow();
      expect(() => parseFilterString('invalid_field = "test"')).toThrow(
        /is not supported/
      );
    });
  });

  describe("searchAndWaitForDone", () => {
    beforeEach(() => {
      vi.useFakeTimers();
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it("should return immediately when condition is met on first attempt", async () => {
      const mockSearchFn = vi.fn().mockResolvedValue([1, 2, 3, 4, 5]);

      const promise = searchAndWaitForDone(mockSearchFn, 5, 10000, 1000);

      await vi.runAllTimersAsync();
      const result = await promise;

      expect(result).toEqual([1, 2, 3, 4, 5]);
      expect(mockSearchFn).toHaveBeenCalledTimes(1);
    });

    it("should poll until condition is met", async () => {
      const mockSearchFn = vi
        .fn()
        .mockResolvedValueOnce([1])
        .mockResolvedValueOnce([1, 2])
        .mockResolvedValueOnce([1, 2, 3]);

      const promise = searchAndWaitForDone(mockSearchFn, 3, 10000, 1000);

      await vi.runAllTimersAsync();
      const result = await promise;

      expect(result).toEqual([1, 2, 3]);
      expect(mockSearchFn).toHaveBeenCalledTimes(3);
    });

    it("should return partial results when timeout is reached", async () => {
      const mockSearchFn = vi
        .fn()
        .mockResolvedValueOnce([1])
        .mockResolvedValueOnce([1, 2])
        .mockResolvedValue([1, 2]);

      const promise = searchAndWaitForDone(mockSearchFn, 10, 2000, 1000);

      await vi.runAllTimersAsync();
      const result = await promise;

      expect(result).toEqual([1, 2]);
      expect(result.length).toBeLessThan(10);
    });

    it("should respect sleep intervals between polling attempts", async () => {
      const mockSearchFn = vi
        .fn()
        .mockResolvedValueOnce([1])
        .mockResolvedValueOnce([1, 2, 3]);

      const promise = searchAndWaitForDone(mockSearchFn, 3, 10000, 5000);

      await vi.advanceTimersByTimeAsync(0);
      expect(mockSearchFn).toHaveBeenCalledTimes(1);

      await vi.advanceTimersByTimeAsync(5000);
      expect(mockSearchFn).toHaveBeenCalledTimes(2);

      await promise;
    });

    it("should propagate search function errors", async () => {
      const mockSearchFn = vi
        .fn()
        .mockRejectedValue(new Error("Search failed"));

      const promise = searchAndWaitForDone(mockSearchFn, 5, 10000, 1000);

      await expect(promise).rejects.toThrow("Search failed");
    });
  });

  describe("searchTracesWithFilters", () => {
    let mockApiClient: OpikApiClientTemp;
    let parseNdjsonSpy: MockInstance;

    beforeEach(() => {
      mockApiClient = {
        traces: {
          searchTraces: vi.fn(),
        },
      } as unknown as OpikApiClientTemp;

      parseNdjsonSpy = vi
        .spyOn(streamUtils, "parseNdjsonStreamToArray")
        .mockResolvedValue([]);
    });

    afterEach(() => {
      parseNdjsonSpy.mockRestore();
    });

    it("should call API with correct parameters and return parsed traces", async () => {
      const mockTraces = createMockTraces(2);
      parseNdjsonSpy.mockResolvedValue(mockTraces);

      const mockStream = {} as AsyncIterable<Uint8Array>;
      vi.spyOn(mockApiClient.traces, "searchTraces").mockResolvedValue(
        mockStream as never
      );

      const filters: OpikApi.TraceFilterPublic[] = [
        { field: "name", operator: "=", value: "Test" },
      ];

      const result = await searchTracesWithFilters(
        mockApiClient,
        "test-project",
        filters,
        100,
        true
      );

      expect(mockApiClient.traces.searchTraces).toHaveBeenCalledWith({
        projectName: "test-project",
        filters,
        limit: 100,
        truncate: true,
      });
      expect(result).toEqual(mockTraces);
    });

    it("should handle null filters and pass as undefined to API", async () => {
      const mockStream = {} as AsyncIterable<Uint8Array>;
      vi.spyOn(mockApiClient.traces, "searchTraces").mockResolvedValue(
        mockStream as never
      );

      await searchTracesWithFilters(
        mockApiClient,
        "test-project",
        null,
        100,
        true
      );

      expect(mockApiClient.traces.searchTraces).toHaveBeenCalledWith(
        expect.objectContaining({
          filters: undefined,
        })
      );
    });

    it("should handle complex filters with metadata and feedback keys", async () => {
      const mockStream = {} as AsyncIterable<Uint8Array>;
      vi.spyOn(mockApiClient.traces, "searchTraces").mockResolvedValue(
        mockStream as never
      );

      const filters: OpikApi.TraceFilterPublic[] = [
        {
          field: "metadata",
          key: "version",
          operator: "=",
          value: "1.0",
        },
        {
          field: "feedback_scores",
          key: "quality",
          operator: ">",
          value: "0.8",
        },
      ];

      await searchTracesWithFilters(
        mockApiClient,
        "test-project",
        filters,
        100,
        true
      );

      const call = (
        mockApiClient.traces.searchTraces as ReturnType<typeof vi.fn>
      ).mock.calls[0][0];
      expect(call.filters).toEqual(filters);
    });
  });

  describe("parseSpanFilterString", () => {
    it("should return null for empty or undefined filter strings", () => {
      expect(parseSpanFilterString(undefined)).toBeNull();
      expect(parseSpanFilterString("")).toBeNull();
    });

    it.each([
      {
        input: 'model = "gpt-4"',
        expected: { field: "model", operator: "=", value: "gpt-4" },
      },
      {
        input: 'provider = "openai"',
        expected: { field: "provider", operator: "=", value: "openai" },
      },
      {
        input: 'type = "llm"',
        expected: { field: "type", operator: "=", value: "llm" },
      },
      {
        input: 'model != "gpt-3.5"',
        expected: { field: "model", operator: "!=", value: "gpt-3.5" },
      },
      {
        input: 'provider contains "open"',
        expected: { field: "provider", operator: "contains", value: "open" },
      },
      {
        input: "duration > 100",
        expected: { field: "duration", operator: ">", value: "100" },
      },
    ])("should parse span filter: $input", ({ input, expected }) => {
      const result = parseSpanFilterString(input);
      expect(result).toHaveLength(1);
      expect(result![0]).toMatchObject(expected);
    });

    it("should parse filters with keys for metadata and feedback_scores", () => {
      const metadataResult = parseSpanFilterString('metadata.version = "1.0"');
      expect(metadataResult![0]).toMatchObject({
        field: "metadata",
        key: "version",
        operator: "=",
        value: "1.0",
      });

      const feedbackResult = parseSpanFilterString(
        'feedback_scores."quality" > 0.8'
      );
      expect(feedbackResult![0]).toMatchObject({
        field: "feedback_scores",
        key: "quality",
        operator: ">",
        value: "0.8",
      });
    });

    it("should parse complex queries with multiple conditions", () => {
      const result = parseSpanFilterString(
        'model = "gpt-4" and provider = "openai" and type = "llm"'
      );

      expect(result).toHaveLength(3);
      expect(result![0].field).toBe("model");
      expect(result![1].field).toBe("provider");
      expect(result![2].field).toBe("type");
    });

    it("should parse usage field filters", () => {
      const result = parseSpanFilterString("usage.total_tokens > 1000");

      expect(result).toHaveLength(1);
      expect(result![0]).toMatchObject({
        field: "usage.total_tokens",
        operator: ">",
        value: "1000",
      });
    });

    it("should throw error for invalid OQL syntax", () => {
      expect(() => parseSpanFilterString("invalid syntax ===")).toThrow();
    });
  });

  describe("searchSpansWithFilters", () => {
    let mockApiClient: OpikApiClientTemp;
    let parseNdjsonSpy: MockInstance;

    beforeEach(() => {
      mockApiClient = {
        spans: {
          searchSpans: vi.fn(),
        },
      } as unknown as OpikApiClientTemp;

      parseNdjsonSpy = vi
        .spyOn(streamUtils, "parseNdjsonStreamToArray")
        .mockResolvedValue([]);
    });

    afterEach(() => {
      parseNdjsonSpy.mockRestore();
    });

    it("should call API with correct parameters and return parsed spans", async () => {
      const mockSpans = createMockSpans(2);
      parseNdjsonSpy.mockResolvedValue(mockSpans);

      const mockStream = {} as AsyncIterable<Uint8Array>;
      vi.spyOn(mockApiClient.spans, "searchSpans").mockResolvedValue(
        mockStream as never
      );

      const filters: OpikApi.SpanFilterPublic[] = [
        { field: "model", operator: "=", value: "gpt-4" },
      ];

      const result = await searchSpansWithFilters(
        mockApiClient,
        "test-project",
        filters,
        100,
        true
      );

      expect(mockApiClient.spans.searchSpans).toHaveBeenCalledWith({
        projectName: "test-project",
        filters,
        limit: 100,
        truncate: true,
      });
      expect(result).toEqual(mockSpans);
    });

    it("should handle null filters and pass as undefined to API", async () => {
      const mockStream = {} as AsyncIterable<Uint8Array>;
      vi.spyOn(mockApiClient.spans, "searchSpans").mockResolvedValue(
        mockStream as never
      );

      await searchSpansWithFilters(
        mockApiClient,
        "test-project",
        null,
        100,
        true
      );

      expect(mockApiClient.spans.searchSpans).toHaveBeenCalledWith(
        expect.objectContaining({
          filters: undefined,
        })
      );
    });

    it("should handle complex filters with metadata and feedback keys", async () => {
      const mockStream = {} as AsyncIterable<Uint8Array>;
      vi.spyOn(mockApiClient.spans, "searchSpans").mockResolvedValue(
        mockStream as never
      );

      const filters: OpikApi.SpanFilterPublic[] = [
        {
          field: "metadata",
          key: "version",
          operator: "=",
          value: "1.0",
        },
        {
          field: "feedback_scores",
          key: "quality",
          operator: ">",
          value: "0.8",
        },
        {
          field: "model",
          operator: "=",
          value: "gpt-4",
        },
      ];

      await searchSpansWithFilters(
        mockApiClient,
        "test-project",
        filters,
        100,
        true
      );

      const call = (
        mockApiClient.spans.searchSpans as ReturnType<typeof vi.fn>
      ).mock.calls[0][0];
      expect(call.filters).toEqual(filters);
    });
  });
});
