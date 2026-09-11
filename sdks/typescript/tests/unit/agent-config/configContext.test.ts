import { agentConfigContext, getActiveConfigMask, getActiveConfigBlueprintName } from "@/agent-config/configContext";

describe("agentConfigContext", () => {
  it("propagates maskId within the callback", async () => {
    let capturedMask: string | null = null;

    await agentConfigContext({ maskId: "mask-abc" }, async () => {
      capturedMask = getActiveConfigMask();
    });

    expect(capturedMask).toBe("mask-abc");
  });

  it("propagates blueprintName within the callback", async () => {
    let capturedName: string | null = null;

    await agentConfigContext({ blueprintName: "my-version" }, async () => {
      capturedName = getActiveConfigBlueprintName();
    });

    expect(capturedName).toBe("my-version");
  });

  it("propagates both blueprintName and maskId", async () => {
    let capturedMask: string | null = null;
    let capturedName: string | null = null;

    await agentConfigContext({ blueprintName: "my-version", maskId: "mask-abc" }, async () => {
      capturedMask = getActiveConfigMask();
      capturedName = getActiveConfigBlueprintName();
    });

    expect(capturedMask).toBe("mask-abc");
    expect(capturedName).toBe("my-version");
  });

  it("returns null outside the context", () => {
    expect(getActiveConfigMask()).toBeNull();
    expect(getActiveConfigBlueprintName()).toBeNull();
  });

  it("isolates nested contexts", async () => {
    let outer: string | null = null;
    let inner: string | null = null;
    let afterInner: string | null = null;

    await agentConfigContext({ maskId: "outer-mask" }, async () => {
      outer = getActiveConfigMask();
      await agentConfigContext({ maskId: "inner-mask" }, async () => {
        inner = getActiveConfigMask();
      });
      afterInner = getActiveConfigMask();
    });

    expect(outer).toBe("outer-mask");
    expect(inner).toBe("inner-mask");
    expect(afterInner).toBe("outer-mask");
  });

  it("works with sync callbacks", () => {
    let captured: string | null = null;
    agentConfigContext({ maskId: "sync-mask" }, () => {
      captured = getActiveConfigMask();
    });
    expect(captured).toBe("sync-mask");
  });
});
