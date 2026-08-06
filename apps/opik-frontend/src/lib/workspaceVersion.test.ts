import { describe, it, expect, beforeEach } from "vitest";
import { resolveSyncWorkspaceVersion } from "./workspaceVersion";

describe("resolveSyncWorkspaceVersion", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  // Regression guard: resolveSyncWorkspaceVersion must always return v2 and must not read legacy
  // local state. Each case seeds a key the function must ignore (override, per-workspace cache); if
  // a future change re-introduces the localStorage read — the stale-v1 read behind the Opik Connect
  // incident — the override/cache case would resolve "v1" and fail here.
  it.each([
    ["no local state", () => {}],
    [
      "a legacy v1 override",
      () => localStorage.setItem("opik-version-override", "v1"),
    ],
    [
      "a stale cached v1 for the workspace",
      () =>
        localStorage.setItem(
          "opik-workspace-versions",
          JSON.stringify({ "ws-1": "v1" }),
        ),
    ],
  ])("returns v2 with %s", (_label, seedLocalState) => {
    seedLocalState();
    expect(resolveSyncWorkspaceVersion()).toBe("v2");
  });
});
