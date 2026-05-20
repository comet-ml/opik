import { describe, it, expect } from "vitest";
import {
  promptMaskContext,
  getActiveMaskForPrompt,
} from "@/prompt/maskContext";

describe("promptMaskContext", () => {
  it("returns null outside any context", () => {
    expect(getActiveMaskForPrompt("prompt-1")).toBeNull();
  });

  it("returns null for unknown prompt id inside context", () => {
    let result: string | null = "sentinel";
    promptMaskContext({ "prompt-1": "mask-a" }, () => {
      result = getActiveMaskForPrompt("unknown-prompt");
    });
    expect(result).toBeNull();
  });

  it("returns mask for known prompt id inside context", () => {
    let result: string | null = null;
    promptMaskContext({ "prompt-1": "mask-a", "prompt-2": "mask-b" }, () => {
      result = getActiveMaskForPrompt("prompt-2");
    });
    expect(result).toBe("mask-b");
  });

  it("returns null after context exits", () => {
    promptMaskContext({ "prompt-1": "mask-a" }, () => {});
    expect(getActiveMaskForPrompt("prompt-1")).toBeNull();
  });

  it("returns null when context is explicitly null", () => {
    let result: string | null = "sentinel";
    promptMaskContext(null, () => {
      result = getActiveMaskForPrompt("prompt-1");
    });
    expect(result).toBeNull();
  });

  it("returns null when context is undefined", () => {
    let result: string | null = "sentinel";
    promptMaskContext(undefined, () => {
      result = getActiveMaskForPrompt("prompt-1");
    });
    expect(result).toBeNull();
  });

  it("nested contexts: inner mask overrides outer for same prompt", () => {
    let outerResult: string | null = null;
    let innerResult: string | null = null;
    let afterInnerResult: string | null = null;

    promptMaskContext({ "prompt-1": "mask-outer" }, () => {
      outerResult = getActiveMaskForPrompt("prompt-1");
      promptMaskContext({ "prompt-1": "mask-inner" }, () => {
        innerResult = getActiveMaskForPrompt("prompt-1");
      });
      afterInnerResult = getActiveMaskForPrompt("prompt-1");
    });

    expect(outerResult).toBe("mask-outer");
    expect(innerResult).toBe("mask-inner");
    expect(afterInnerResult).toBe("mask-outer");
  });

  it("works with async callbacks", async () => {
    let result: string | null = null;
    await promptMaskContext({ "prompt-1": "mask-async" }, async () => {
      await new Promise((resolve) => setTimeout(resolve, 1));
      result = getActiveMaskForPrompt("prompt-1");
    });
    expect(result).toBe("mask-async");
  });

  it("does not leak context to sibling callbacks", () => {
    let sibling: string | null = "sentinel";
    promptMaskContext({ "prompt-1": "mask-a" }, () => {});
    promptMaskContext({}, () => {
      sibling = getActiveMaskForPrompt("prompt-1");
    });
    expect(sibling).toBeNull();
  });
});
