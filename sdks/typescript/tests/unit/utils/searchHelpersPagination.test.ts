import { describe, it, expect, vi } from "vitest";
import { searchTracesWithFilters } from "@/utils/searchHelpers";
import { OpikApiClientTemp } from "@/client/OpikApiClientTemp";
import { createMockStream } from "../../mockUtils";

describe("search pagination", () => {
  it("reads past the 2000 limit page by page with the last id as cursor", async () => {
    const records = Array.from({ length: 4500 }, (_, i) => ({
      id: `id-${String(4500 - i).padStart(8, "0")}`,
      start_time: "2024-01-01T00:00:00Z",
    }));
    const searchTraces = vi.fn(
      async (request: { limit: number; lastRetrievedId?: string }) => {
        if (request.limit > 2000) {
          throw new Error("limit must be less than or equal to 2000");
        }
        const page = records
          .filter(
            (r) =>
              request.lastRetrievedId === undefined ||
              r.id < request.lastRetrievedId
          )
          .slice(0, request.limit);
        return createMockStream(page.map((r) => JSON.stringify(r)).join("\n"));
      }
    );
    const api = { traces: { searchTraces } } as unknown as OpikApiClientTemp;

    const result = await searchTracesWithFilters(
      api,
      "test-project",
      null,
      1_000_000,
      true
    );

    expect(result.map((t) => t.id)).toEqual(records.map((r) => r.id));
    expect(searchTraces.mock.calls.map(([r]) => r.lastRetrievedId)).toEqual([
      undefined,
      records[1999].id,
      records[3999].id,
    ]);
  });
});
