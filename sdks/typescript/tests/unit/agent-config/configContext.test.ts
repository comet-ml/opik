import { agentConfigContext, getActiveConfigMask } from "@/agent-config/configContext";

describe("agentConfigContext", () => {
  it("propagates maskId within the callback", async () => {
    let capturedMask: string | null = null;

    await agentConfigContext("mask-abc", async () => {
      capturedMask = getActiveConfigMask();
    });

    expect(capturedMask).toBe("mask-abc");
  });

  it("returns null outside the context", () => {
    expect(getActiveConfigMask()).toBeNull();
  });

  it("isolates nested contexts", async () => {
    let outer: string | null = null;
    let inner: string | null = null;
    let afterInner: string | null = null;

    await agentConfigContext("outer-mask", async () => {
      outer = getActiveConfigMask();
      await agentConfigContext("inner-mask", async () => {
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
    agentConfigContext("sync-mask", () => {
      captured = getActiveConfigMask();
    });
    expect(captured).toBe("sync-mask");
  });
});
