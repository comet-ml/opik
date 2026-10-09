import { afterEach, describe, expect, it, vi } from "vitest";
import { loader } from "vega";
import { prepareVegaSpec, withoutNetwork } from "./vega";

describe("withoutNetwork", () => {
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it.each([
    ["an external url", "https://attacker.example/collect"],
    [
      "a same-origin API read",
      "/opik/api/v1/private/projects?workspace_name=other",
    ],
  ])("refuses to load %s without fetching it", async (_, uri) => {
    const fetchSpy = vi.spyOn(globalThis, "fetch");

    await expect(withoutNetwork(loader()).load(uri)).rejects.toThrow();
    expect(fetchSpy).not.toHaveBeenCalled();
  });

  it.each(["image", "href"] as const)("refuses an %s url", async (context) => {
    await expect(
      withoutNetwork(loader()).sanitize("https://attacker.example/", {
        context,
      }),
    ).rejects.toThrow();
  });
});

describe("prepareVegaSpec", () => {
  it("drops usermeta, so a stored spec cannot override the embed options", () => {
    const prepared = prepareVegaSpec(
      {
        spec: {
          mark: "bar",
          data: { name: "rows" },
          usermeta: { embedOptions: { ast: false, patch: [] } },
        },
        rows: [{ x: "a", y: 1 }],
      },
      {},
    );

    expect(prepared).not.toHaveProperty("usermeta");
  });
});
