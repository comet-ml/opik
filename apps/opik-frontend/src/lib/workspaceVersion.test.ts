import { describe, it, expect, beforeEach } from "vitest";
import { resolveSyncWorkspaceVersion } from "./workspaceVersion";

describe("resolveSyncWorkspaceVersion", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  // Opik V2 is the only supported experience. This must hold regardless of any legacy local
  // state — a stale `opik-version-override=v1` / cached `v1` is exactly what forced a workspace
  // into V1App and produced the "Opik Connect requires Opik 2.0" error screen. Forcing v2 here is
  // the client-side replacement for the removed backend forceWorkspaceVersion default.
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
