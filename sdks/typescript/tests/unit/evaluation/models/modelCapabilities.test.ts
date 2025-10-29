import { describe, it, expect } from "vitest";
import { ModelCapabilities } from "@/evaluation/models/modelCapabilities";

describe("ModelCapabilities.supportsVision", () => {
  it("returns false when model name is missing", () => {
    expect(ModelCapabilities.supportsVision(undefined)).toBe(false);
  });

  it("detects vision support for known model ids", () => {
    expect(ModelCapabilities.supportsVision("gpt-4o")).toBe(true);
  });

  it("detects vision support when provider prefix uses colon separator", () => {
    expect(ModelCapabilities.supportsVision("openai:gpt-4o")).toBe(true);
  });

  it("detects vision support when provider prefix uses slash separator", () => {
    expect(
      ModelCapabilities.supportsVision("anthropic/claude-3-5-sonnet")
    ).toBe(true);
  });
});
