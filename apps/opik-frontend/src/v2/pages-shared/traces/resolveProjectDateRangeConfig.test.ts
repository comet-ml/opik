import { describe, it, expect } from "vitest";
import { DEMO_PROJECT_NAME, DEMO_PROJECT_NAMES } from "@/constants/shared";
import {
  DATE_RANGE_PRESET_PAST_24_HOURS,
  DEFAULT_DATE_PRESET,
} from "./MetricDateRangeSelect/constants";
import { resolveProjectDateRangeConfig } from "./resolveProjectDateRangeConfig";

describe("resolveProjectDateRangeConfig", () => {
  it("should default the demo project to 24 hours so its charts bucket hourly", () => {
    const result = resolveProjectDateRangeConfig(DEMO_PROJECT_NAME);

    expect(result.defaultValue).toBe(DATE_RANGE_PRESET_PAST_24_HOURS);
  });

  // Matching is by membership in DEMO_PROJECT_NAMES, so every name in the list has to get the
  // treatment — otherwise adding a rename there would silently do nothing.
  it.each([...DEMO_PROJECT_NAMES])(
    "should treat %s as a demo project",
    (name) => {
      const result = resolveProjectDateRangeConfig(name);

      expect(result.defaultValue).toBe(DATE_RANGE_PRESET_PAST_24_HOURS);
      expect(result.storageKeySuffix).toBe(`-${name}`);
    },
  );

  it("should scope the storage slot to the matched name, not a shared literal", () => {
    // Two demo names must not collide in one slot.
    const suffixes = [...DEMO_PROJECT_NAMES].map(
      (name) => resolveProjectDateRangeConfig(name).storageKeySuffix,
    );

    expect(new Set(suffixes).size).toBe(suffixes.length);
  });

  it("should leave other projects on the workspace-wide default", () => {
    const result = resolveProjectDateRangeConfig("My Real Project");

    expect(result.defaultValue).toBe(DEFAULT_DATE_PRESET);
  });

  it("should treat an unknown name as not the demo project", () => {
    // Callers gate on the project query, so undefined here means a failed lookup rather than
    // "still loading" — the workspace default is the right answer.
    const result = resolveProjectDateRangeConfig(undefined);

    expect(result.defaultValue).toBe(DEFAULT_DATE_PRESET);
    expect(result.storageKeySuffix).toBe("");
  });

  it("should not mistake the raw project id for a project name", () => {
    // LogsPage's own `projectName` falls back to the id while loading; callers must pass
    // project?.name instead, and an id must never read as the demo project.
    const result = resolveProjectDateRangeConfig(
      "019feaba-9c9b-71c3-93f5-905be65789c5",
    );

    expect(result.defaultValue).toBe(DEFAULT_DATE_PRESET);
    expect(result.storageKeySuffix).toBe("");
  });

  // The stored range is sticky across projects and outranks any defaultValue, so without its own
  // storage slot the demo project would inherit whatever range was last picked on a real project
  // and the 24h default would never apply.
  describe("storageKeySuffix", () => {
    it("should give the demo project its own storage slot, named after the project", () => {
      const result = resolveProjectDateRangeConfig(DEMO_PROJECT_NAME);

      expect(result.storageKeySuffix).toBe(`-${DEMO_PROJECT_NAME}`);
    });

    it("should not treat a name absent from the list as a demo project", () => {
      const result = resolveProjectDateRangeConfig("Opik Demo Questions");

      expect(result.defaultValue).toBe(DEFAULT_DATE_PRESET);
      expect(result.storageKeySuffix).toBe("");
    });

    it("should leave other projects on the shared storage slot", () => {
      const result = resolveProjectDateRangeConfig("My Real Project");

      expect(result.storageKeySuffix).toBe("");
    });

    it("should not claim the demo slot before the name resolves", () => {
      const result = resolveProjectDateRangeConfig(undefined);

      expect(result.storageKeySuffix).toBe("");
    });
  });
});
