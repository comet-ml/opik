import { describe, it, expect, vi } from "vitest";
import {
  MAX_SEARCH_BATCH_SIZE,
  searchAllPages,
  searchSpansWithFilters,
  searchThreadsWithFilters,
  searchTracesWithFilters,
} from "@/utils/searchHelpers";
import { OpikApiClientTemp } from "@/client/OpikApiClientTemp";
import { serialization } from "@/rest_api";
import { createMockStream } from "../../mockUtils";

type RawRecord = Record<string, unknown>;

const START_TIME = "2024-01-01T00:00:00Z";

// Ids sort the same way as strings and as the backend UUIDv7 order.
const makeId = (i: number) => `id-${String(i).padStart(8, "0")}`;

const makeRecords = (count: number, cursorField = "id"): RawRecord[] =>
  Array.from({ length: count }, (_, i) => ({
    id: cursorField === "id" ? makeId(count - i) : `thread-${count - i}`,
    ...(cursorField === "id" ? {} : { [cursorField]: makeId(count - i) }),
    start_time: START_TIME,
  }));

const toStream = (records: RawRecord[]) =>
  createMockStream(records.map((r) => JSON.stringify(r)).join("\n"));

// Mimics the backend: records sorted by cursor DESC, `cursor < last` paging.
const fakeBackend = (records: RawRecord[], cursorField: string) =>
  vi.fn(
    async (request: {
      limit?: number;
      lastRetrievedId?: string;
      lastRetrievedThreadModelId?: string;
    }) => {
      const limit = request.limit ?? 500;
      if (limit > MAX_SEARCH_BATCH_SIZE) {
        throw new Error("limit must be less than or equal to 2000");
      }
      const last = request.lastRetrievedId ?? request.lastRetrievedThreadModelId;
      const page = records
        .filter((r) => last === undefined || String(r[cursorField]) < last)
        .slice(0, limit);
      return toStream(page);
    }
  );

const createApiClient = (records: RawRecord[], cursorField = "id") =>
  ({
    traces: {
      searchTraces: fakeBackend(records, cursorField),
      searchTraceThreads: fakeBackend(records, cursorField),
    },
    spans: {
      searchSpans: fakeBackend(records, cursorField),
    },
  }) as unknown as OpikApiClientTemp & {
    traces: {
      searchTraces: ReturnType<typeof fakeBackend>;
      searchTraceThreads: ReturnType<typeof fakeBackend>;
    };
    spans: { searchSpans: ReturnType<typeof fakeBackend> };
  };

const requestsOf = (mock: ReturnType<typeof fakeBackend>) =>
  mock.mock.calls.map(([request]) => request);

describe("search pagination", () => {
  it("reads traces past the 2000 limit page by page with the last id as cursor", async () => {
    const records = makeRecords(4500);
    const api = createApiClient(records);

    const result = await searchTracesWithFilters(
      api,
      "test-project",
      null,
      1_000_000,
      true
    );

    expect(result).toHaveLength(4500);
    expect(result[0].id).toBe(makeId(4500));
    expect(result[4499].id).toBe(makeId(1));
    expect(new Set(result.map((t) => t.id)).size).toBe(4500);

    const requests = requestsOf(api.traces.searchTraces);
    expect(requests.map((r) => r.limit)).toEqual([2000, 2000, 2000]);
    expect(requests.map((r) => r.lastRetrievedId)).toEqual([
      undefined,
      records[1999].id,
      records[3999].id,
    ]);
  });

  it("stops when maxResults is reached and asks only for the remaining items", async () => {
    const api = createApiClient(makeRecords(5000));

    const result = await searchTracesWithFilters(
      api,
      "test-project",
      null,
      2500,
      true
    );

    expect(result).toHaveLength(2500);
    expect(requestsOf(api.traces.searchTraces).map((r) => r.limit)).toEqual([
      2000, 500,
    ]);
  });

  it("makes one request when the first page is short", async () => {
    const api = createApiClient(makeRecords(10));

    const result = await searchTracesWithFilters(
      api,
      "test-project",
      null,
      1000,
      true
    );

    expect(result).toHaveLength(10);
    expect(api.traces.searchTraces).toHaveBeenCalledTimes(1);
    expect(api.traces.searchTraces).toHaveBeenCalledWith(
      expect.objectContaining({ limit: 1000, lastRetrievedId: undefined })
    );
  });

  it("keeps filters, truncate and exclude on every page of spans", async () => {
    const records = makeRecords(2100);
    const api = createApiClient(records);
    const filters = [{ field: "model", operator: "=", value: "gpt-4" }] as never;

    const result = await searchSpansWithFilters(
      api,
      "test-project",
      filters,
      5000,
      false,
      ["input"]
    );

    expect(result).toHaveLength(2100);
    const requests = requestsOf(api.spans.searchSpans);
    expect(requests).toHaveLength(2);
    for (const request of requests) {
      expect(request).toMatchObject({
        projectName: "test-project",
        filters,
        truncate: false,
        exclude: ["input"],
      });
    }
    expect(requests[1].lastRetrievedId).toBe(records[1999].id);
  });

  it("pages threads with thread_model_id as the cursor", async () => {
    const records = makeRecords(2001, "thread_model_id");
    const api = createApiClient(records, "thread_model_id");

    const result = await searchThreadsWithFilters(
      api,
      "test-project",
      null,
      3000,
      true
    );

    expect(result).toHaveLength(2001);
    const requests = requestsOf(api.traces.searchTraceThreads);
    expect(requests.map((r) => r.lastRetrievedThreadModelId)).toEqual([
      undefined,
      records[1999].thread_model_id,
    ]);
    expect(requests.every((r) => r.lastRetrievedId === undefined)).toBe(true);
  });

  it("uses the last id the backend sent as cursor even when that record is invalid", async () => {
    const pages: RawRecord[][] = [
      [
        { id: "c", start_time: START_TIME },
        { id: "b", start_time: "not-a-date" },
      ],
      [{ id: "a", start_time: START_TIME }],
    ];
    const fetchPage = vi.fn(async () => toStream(pages.shift() ?? []));

    const result = await searchAllPages(
      fetchPage,
      serialization.TracePublic,
      "id",
      100,
      2
    );

    expect(result.map((t) => t.id)).toEqual(["c", "a"]);
    expect(fetchPage.mock.calls).toEqual([
      [2, undefined],
      [2, "b"],
    ]);
  });

  it("stops when a full page has no id to use as cursor", async () => {
    const fetchPage = vi.fn(async () =>
      toStream([{ start_time: START_TIME }, { start_time: START_TIME }])
    );

    const result = await searchAllPages(
      fetchPage,
      serialization.TracePublic,
      "id",
      100,
      2
    );

    expect(result).toHaveLength(2);
    expect(fetchPage).toHaveBeenCalledTimes(1);
  });

  it("makes no request when maxResults is 0", async () => {
    const fetchPage = vi.fn();

    const result = await searchAllPages(
      fetchPage,
      serialization.TracePublic,
      "id",
      0
    );

    expect(result).toEqual([]);
    expect(fetchPage).not.toHaveBeenCalled();
  });
});
