import { describe, expect, it } from "vitest";
import { Workspace } from "../types";
import { pickLandingWorkspace } from "./aiSpend";

const ws = (workspaceName: string, isDefault = false): Workspace => ({
  workspaceId: workspaceName,
  workspaceName,
  organizationId: "org",
  default: isDefault,
});

describe("pickLandingWorkspace", () => {
  it("prefers the default workspace", () => {
    expect(
      pickLandingWorkspace([ws("__ai_spend_org__"), ws("a"), ws("b", true)])
        ?.workspaceName,
    ).toBe("b");
  });

  it("skips the spend workspace when no default is set", () => {
    expect(
      pickLandingWorkspace([ws("__ai_spend_org__"), ws("a")])?.workspaceName,
    ).toBe("a");
  });

  it("falls back to the spend workspace when it is the only one", () => {
    expect(pickLandingWorkspace([ws("__ai_spend_org__")])?.workspaceName).toBe(
      "__ai_spend_org__",
    );
  });
});
