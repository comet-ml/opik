import { describe, it, expect } from "vitest";
import { BRIDGE_PROTOCOL_VERSION } from "./assistant-sidebar";

// Single-repo change-awareness canary (NOT a true cross-repo guard): the shell
// side lives in `ollie-console/src/bridge.ts`, which this repo can't import, so
// this test can only catch an *accidental* local edit/deletion of the version —
// it cannot detect an independent bump on the console side. Treat a failure here
// as a prompt to follow the manual lockstep checklist: bump BRIDGE_PROTOCOL_VERSION
// here AND in ollie-console/src/bridge.ts (whose VER-4 asserts the event shapes)
// together, then update the expectation below.
describe("bridge protocol", () => {
  it("is pinned to the version both sides must agree on", () => {
    expect(BRIDGE_PROTOCOL_VERSION).toBe(2);
  });
});
