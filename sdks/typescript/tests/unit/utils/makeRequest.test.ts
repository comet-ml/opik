import { makeRequest } from "@/rest_api/core/fetcher/makeRequest";

describe("makeRequest", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it("clears timeout when fetch succeeds", async () => {
    const clearTimeoutSpy = vi.spyOn(globalThis, "clearTimeout");
    const fetchFn = vi
      .fn()
      .mockResolvedValue(new Response("ok", { status: 200 }));

    await makeRequest(
      fetchFn,
      "http://localhost:8080",
      "GET",
      {},
      undefined,
      1000
    );

    expect(clearTimeoutSpy).toHaveBeenCalledTimes(1);
  });

  it("clears timeout when fetch fails", async () => {
    const clearTimeoutSpy = vi.spyOn(globalThis, "clearTimeout");
    const fetchFn = vi.fn().mockRejectedValue(new Error("fetch failed"));

    await expect(
      makeRequest(fetchFn, "http://localhost:8080", "GET", {}, undefined, 1000)
    ).rejects.toThrow("fetch failed");

    expect(clearTimeoutSpy).toHaveBeenCalledTimes(1);
  });
});
